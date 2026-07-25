#![allow(dead_code)]

#[path = "../../engine/aether/src/account.rs"]
mod account;
#[path = "../../engine/aether/src/aethernoize.rs"]
mod aethernoize;
#[path = "../../engine/aether/src/config.rs"]
mod config;
#[path = "../../engine/aether/src/consts.rs"]
mod consts;
#[path = "../../engine/aether/src/error.rs"]
mod error;
#[path = "../../engine/aether/src/fragment.rs"]
mod fragment;
#[path = "../../engine/aether/src/masque.rs"]
mod masque;
#[path = "../../engine/aether/src/masque_h2.rs"]
mod masque_h2;
#[path = "../../engine/aether/src/netstack.rs"]
mod netstack;
#[path = "../../engine/aether/src/noize.rs"]
mod noize;
#[path = "../../engine/aether/src/prober.rs"]
mod prober;
#[path = "../../engine/aether/src/quic.rs"]
mod quic;
#[path = "../../engine/aether/src/socks.rs"]
mod socks;
#[path = "../../engine/aether/src/sysprofile.rs"]
mod sysprofile;
#[path = "../../engine/aether/src/tls.rs"]
mod tls;
#[path = "../../engine/aether/src/tunnelping.rs"]
mod tunnelping;
#[path = "../../engine/aether/src/wg_prober.rs"]
mod wg_prober;
#[path = "../../engine/aether/src/wireguard.rs"]
mod wireguard;

use std::collections::HashMap;
use std::ffi::CString;
use std::fs::File;
use std::net::{Ipv4Addr, SocketAddr};
use std::os::fd::{AsRawFd, FromRawFd};
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, Once, OnceLock};
use std::thread;

use error::{AetherError, Result};
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong};
use jni::{JNIEnv, JavaVM};
use serde::Deserialize;
use tokio::io::unix::AsyncFd;
use tokio::sync::{mpsc, oneshot};

const VPN_IPV4: Ipv4Addr = Ipv4Addr::new(172, 31, 19, 2);

static LOG_INIT: Once = Once::new();
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static RUNS: OnceLock<Mutex<HashMap<i64, oneshot::Sender<()>>>> = OnceLock::new();
static LOG_TARGET: OnceLock<Mutex<Option<LogTarget>>> = OnceLock::new();
static LOGGER: AndroidUiLogger = AndroidUiLogger;

struct LogTarget {
    vm: JavaVM,
    callback: GlobalRef,
}

struct AndroidUiLogger;

impl log::Log for AndroidUiLogger {
    fn enabled(&self, metadata: &log::Metadata<'_>) -> bool {
        metadata.level() <= log::Level::Info
    }

    fn log(&self, record: &log::Record<'_>) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let message = record.args().to_string();
        write_android_log(record.level(), &message);
        write_ui_log(record.level(), &message);
    }

    fn flush(&self) {}
}

#[derive(Default)]
struct TrafficStats {
    outbound_packets: AtomicU64,
    outbound_bytes: AtomicU64,
    inbound_packets: AtomicU64,
    inbound_bytes: AtomicU64,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AndroidConfig {
    #[serde(default = "default_protocol")]
    protocol: String,
    #[serde(default = "default_scan")]
    scan_mode: String,
    #[serde(default = "default_ip")]
    ip_mode: String,
    #[serde(default = "default_noize")]
    noize_profile: String,
    config_dir: String,
}

fn default_protocol() -> String {
    "masque-h3".into()
}

fn default_scan() -> String {
    "balanced".into()
}

fn default_ip() -> String {
    "v4".into()
}

fn default_noize() -> String {
    "balanced".into()
}

#[no_mangle]
pub extern "system" fn Java_io_github_lvl45t3r_koku_AetherNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
    tun_fd: jint,
    log_sink: JObject,
) -> jlong {
    init_logging();
    sysprofile::log_summary();
    if let Err(err) = set_log_target(&mut env, log_sink) {
        log::error!("could not attach the in-app logger: {err}");
    }

    let json: String = match env.get_string(&config_json) {
        Ok(value) => value.into(),
        Err(err) => {
            log::error!("invalid JNI config string: {err}");
            close_fd(tun_fd);
            return 0;
        }
    };
    let cfg: AndroidConfig = match serde_json::from_str(&json) {
        Ok(value) => value,
        Err(err) => {
            log::error!("invalid Android config: {err}");
            close_fd(tun_fd);
            return 0;
        }
    };
    if cfg.config_dir.is_empty() {
        log::error!("Android configDir is empty");
        close_fd(tun_fd);
        return 0;
    }

    log::info!(
        "native build uses vendored Koku engine snapshot {}",
        env!("KOKU_ENGINE_SOURCE"),
    );
    log::info!(
        "startup config: protocol={}, scan={}, ip={}, noize={}",
        cfg.protocol,
        cfg.scan_mode,
        cfg.ip_mode,
        cfg.noize_profile,
    );

    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    let (stop_tx, stop_rx) = oneshot::channel();
    runs().lock().unwrap().insert(handle, stop_tx);

    thread::Builder::new()
        .name("aether-native".into())
        .spawn(move || {
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .worker_threads(3)
                .thread_name("aether-io")
                .build();
            match runtime {
                Ok(rt) => match rt.block_on(run_android(cfg, tun_fd, stop_rx)) {
                    Ok(()) => log::info!("Aether engine stopped cleanly"),
                    Err(err) => log::error!("Aether engine stopped with error: {err}"),
                },
                Err(err) => {
                    close_fd(tun_fd);
                    log::error!("failed to create runtime: {err}");
                }
            }
            runs().lock().unwrap().remove(&handle);
        })
        .map(|_| handle)
        .unwrap_or_else(|err| {
            log::error!("failed to start native thread: {err}");
            runs().lock().unwrap().remove(&handle);
            close_fd(tun_fd);
            0
        })
}

#[no_mangle]
pub extern "system" fn Java_io_github_lvl45t3r_koku_AetherNative_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(stop) = runs().lock().unwrap().remove(&handle) {
        log::info!("native stop signal sent for handle {handle}");
        let _ = stop.send(());
    }
}

fn runs() -> &'static Mutex<HashMap<i64, oneshot::Sender<()>>> {
    RUNS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn init_logging() {
    LOG_INIT.call_once(|| {
        if log::set_logger(&LOGGER).is_ok() {
            log::set_max_level(log::LevelFilter::Info);
        }
    });
}

fn set_log_target(env: &mut JNIEnv<'_>, callback: JObject<'_>) -> jni::errors::Result<()> {
    let target = LogTarget {
        vm: env.get_java_vm()?,
        callback: env.new_global_ref(callback)?,
    };
    *LOG_TARGET.get_or_init(|| Mutex::new(None)).lock().unwrap() = Some(target);
    Ok(())
}

fn write_android_log(level: log::Level, message: &str) {
    let priority = match level {
        log::Level::Error => android_log_sys::LogPriority::ERROR,
        log::Level::Warn => android_log_sys::LogPriority::WARN,
        log::Level::Info => android_log_sys::LogPriority::INFO,
        log::Level::Debug => android_log_sys::LogPriority::DEBUG,
        log::Level::Trace => android_log_sys::LogPriority::VERBOSE,
    };
    let tag = c"AetherNative";
    let clean = message.replace('\0', " ");
    if let Ok(text) = CString::new(clean) {
        unsafe {
            android_log_sys::__android_log_write(
                priority as android_log_sys::c_int,
                tag.as_ptr(),
                text.as_ptr(),
            );
        }
    }
}

fn write_ui_log(level: log::Level, message: &str) {
    let guard = LOG_TARGET.get_or_init(|| Mutex::new(None)).lock().unwrap();
    let Some(target) = guard.as_ref() else {
        return;
    };
    let Ok(mut env) = target.vm.attach_current_thread() else {
        return;
    };
    let Ok(level) = env.new_string(level.to_string()) else {
        return;
    };
    let Ok(message) = env.new_string(message) else {
        return;
    };
    let level_object = JObject::from(level);
    let message_object = JObject::from(message);
    let _ = env.call_method(
        target.callback.as_obj(),
        "onNativeLog",
        "(Ljava/lang/String;Ljava/lang/String;)V",
        &[
            JValue::Object(&level_object),
            JValue::Object(&message_object),
        ],
    );
}

fn close_fd(fd: i32) {
    if fd >= 0 {
        unsafe {
            libc::close(fd);
        }
    }
}

async fn run_android(
    cfg: AndroidConfig,
    tun_fd: i32,
    mut stop: oneshot::Receiver<()>,
) -> Result<()> {
    log::info!("initializing vendored Aether engine");
    std::fs::create_dir_all(&cfg.config_dir)?;
    std::env::set_var("AETHER_NOIZE", &cfg.noize_profile);
    std::env::set_var(
        "AETHER_MASQUE_HTTP2",
        if cfg.protocol.eq_ignore_ascii_case("masque-h2") {
            "1"
        } else {
            "0"
        },
    );

    let flags = unsafe { libc::fcntl(tun_fd, libc::F_GETFL) };
    if flags < 0 || unsafe { libc::fcntl(tun_fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        close_fd(tun_fd);
        return Err(AetherError::Io(std::io::Error::last_os_error()));
    }
    let file = unsafe { File::from_raw_fd(tun_fd) };
    let tun = Arc::new(AsyncFd::new(file)?);
    log::info!("Android TUN fd is active and non-blocking");

    if cfg.protocol.eq_ignore_ascii_case("wireguard") || cfg.protocol.eq_ignore_ascii_case("wg") {
        run_wireguard(cfg, tun, &mut stop).await
    } else {
        run_masque(cfg, tun, &mut stop).await
    }
}

async fn load_or_create_identity(path: &str, masque: bool) -> Result<account::Identity> {
    let mut identity = match config::load(path)? {
        Some(value) => value,
        None => {
            log::info!("provisioning a new WARP identity");
            let value =
                account::provision_wg(consts::DEFAULT_MODEL, consts::DEFAULT_LOCALE, None).await?;
            config::save(path, &value)?;
            value
        }
    };
    if masque && !identity.has_masque_credentials() {
        log::info!("enrolling MASQUE credentials");
        let (cert_pem, key_pem) = account::ensure_masque_enrolled(&identity).await?;
        identity.cert_pem = cert_pem;
        identity.key_pem = key_pem;
        config::save(path, &identity)?;
    }
    Ok(identity)
}

async fn run_wireguard(
    cfg: AndroidConfig,
    tun: Arc<AsyncFd<File>>,
    stop: &mut oneshot::Receiver<()>,
) -> Result<()> {
    let path = format!("{}/aether-wireguard.toml", cfg.config_dir);
    let identity = tokio::select! {
        value = load_or_create_identity(&path, false) => value?,
        _ = &mut *stop => return Ok(()),
    };
    let local_ipv4: Ipv4Addr = identity
        .ipv4
        .split('/')
        .next()
        .unwrap_or(&identity.ipv4)
        .parse()
        .map_err(|_| AetherError::Other("invalid identity IPv4".into()))?;
    log::info!("WARP identity ready with tunnel IPv4 {local_ipv4}");
    let private_key = identity.private_key_bytes()?;
    let public_key = identity.peer_public_key_bytes()?;
    let local_ipv6 = identity
        .ipv6
        .split('/')
        .next()
        .unwrap_or(&identity.ipv6)
        .parse()
        .map_err(|_| AetherError::Other("invalid identity IPv6".into()))?;
    let profile = aethernoize::from_profile(&cfg.noize_profile);
    let probe = wg_prober::WgProbe {
        private_key: Arc::new(private_key),
        peer_public_key: Arc::new(public_key),
        client_id: identity.client_id,
        local_ipv4,
        aethernoize: profile.clone(),
        ports: wireguard::WG_PORTS.to_vec(),
        ip: prober::IpScan::parse(&cfg.ip_mode),
    };
    let scan_mode = wg_prober::WgScanMode::parse(&cfg.scan_mode);
    let mut last_good_peer: Option<SocketAddr> = None;

    loop {
        let mut selected_peer = None;
        if let Some(peer) = last_good_peer {
            log::info!("retrying last known-good WireGuard endpoint {peer}");
            let retry = tokio::select! {
                value = wireguard::verify_endpoint(
                    peer,
                    private_key,
                    public_key,
                    identity.client_id,
                    local_ipv4,
                    &profile,
                    std::time::Duration::from_secs(6),
                    None,
                ) => Some(value),
                _ = &mut *stop => None,
            };
            let Some(retry) = retry else {
                return Ok(());
            };
            match retry {
                Ok(_) => {
                    log::info!("last known-good WireGuard endpoint is still healthy");
                    selected_peer = Some(peer);
                }
                Err(error) => {
                    log::warn!(
                        "last known-good WireGuard endpoint failed verification: {error}; rescanning"
                    );
                }
            }
        }

        let peer = match selected_peer {
            Some(peer) => peer,
            None => {
                log::info!("scanning for a working WireGuard endpoint");
                let found = tokio::select! {
                    value = wg_prober::hunt_best_wg_endpoint(&probe, scan_mode) => value?,
                    _ = &mut *stop => return Ok(()),
                };
                SocketAddr::new(found.ip, found.port)
            }
        };
        log::info!("using WireGuard endpoint {peer}");
        last_good_peer = Some(peer);

        let (outbound_tx, outbound_rx) = mpsc::channel(1024);
        let (inbound_tx, inbound_rx) = mpsc::channel(1024);
        let tunnel = tokio::select! {
            value = wireguard::WgTunnel::new(
                wireguard::WgConfig {
                    local_private_key: private_key,
                    peer_public_key: public_key,
                    peer_endpoint: peer,
                    local_ipv4,
                    local_ipv6,
                    client_id: identity.client_id,
                    preshared_key: None,
                    persistent_keepalive: Some(5),
                    aethernoize: Arc::new(profile.clone()),
                },
                inbound_tx,
            ) => value,
            _ = &mut *stop => return Ok(()),
        };
        let tunnel = match tunnel {
            Ok(tunnel) => tunnel,
            Err(error) => {
                log::warn!("WireGuard tunnel setup failed: {error}; reconnecting");
                if reconnect_pause(stop).await {
                    return Ok(());
                }
                continue;
            }
        };
        log::info!("WireGuard data plane is ready");

        match bridge_and_run(
            tun.clone(),
            local_ipv4,
            outbound_tx,
            inbound_rx,
            tunnel.run(outbound_rx),
            stop,
        )
        .await?
        {
            BridgeOutcome::Stopped => return Ok(()),
            BridgeOutcome::TunnelEnded => {
                log::warn!("WireGuard tunnel ended; reconnecting");
                if reconnect_pause(stop).await {
                    return Ok(());
                }
            }
        }
    }
}

async fn run_masque(
    cfg: AndroidConfig,
    tun: Arc<AsyncFd<File>>,
    stop: &mut oneshot::Receiver<()>,
) -> Result<()> {
    let path = format!("{}/aether-masque.toml", cfg.config_dir);
    let identity = tokio::select! {
        value = load_or_create_identity(&path, true) => value?,
        _ = &mut *stop => return Ok(()),
    };
    let local_ipv4: Ipv4Addr = identity
        .ipv4
        .split('/')
        .next()
        .unwrap_or(&identity.ipv4)
        .parse()
        .map_err(|_| AetherError::Other("invalid identity IPv4".into()))?;
    log::info!("WARP identity ready with tunnel IPv4 {local_ipv4}");
    let noise = noize::from_profile(&cfg.noize_profile);
    let probe = prober::MasqueProbe {
        sni: consts::CONNECT_SNI.to_string(),
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: Arc::from(identity.cert_pem.clone()),
        key_pem: Arc::from(identity.key_pem.clone()),
        ech_config_list: None,
        noize: noise.clone(),
        ports: prober::MASQUE_PORTS.to_vec(),
        ip: prober::IpScan::parse(&cfg.ip_mode),
        local_ipv4,
    };
    let h2_enabled = cfg.protocol.eq_ignore_ascii_case("masque-h2");
    let scan_mode = prober::ScanMode::parse(&cfg.scan_mode);
    let mut last_good_peer: Option<SocketAddr> = None;

    loop {
        let mut selected_peer = None;
        if let Some(peer) = last_good_peer {
            log::info!("retrying last known-good MASQUE gateway {peer}");
            let retry = tokio::select! {
                value = verify_masque_peer(
                    &identity,
                    peer,
                    local_ipv4,
                    noise.clone(),
                    h2_enabled,
                ) => Some(value),
                _ = &mut *stop => None,
            };
            let Some(retry) = retry else {
                return Ok(());
            };
            match retry {
                Ok(_) => {
                    log::info!("last known-good MASQUE gateway is still healthy");
                    selected_peer = Some(peer);
                }
                Err(error) => {
                    log::warn!(
                        "last known-good MASQUE gateway failed verification: {error}; rescanning"
                    );
                }
            }
        }

        let peer = match selected_peer {
            Some(peer) => peer,
            None => {
                log::info!("scanning for a working MASQUE endpoint");
                let found = tokio::select! {
                    value = prober::hunt_best_gateway(&probe, scan_mode) => value?,
                    _ = &mut *stop => return Ok(()),
                };
                SocketAddr::new(found.ip, found.port)
            }
        };
        log::info!("using MASQUE endpoint {peer}");
        last_good_peer = Some(peer);

        let (channels, internals) = quic::channels();
        let (ready_tx, ready_rx) = oneshot::channel();
        let tunnel = if h2_enabled {
            let h2 = masque_h2::H2TunnelConfig {
                peer: masque_h2::h2_peer(peer),
                sni: consts::CONNECT_SNI.to_string(),
                authority: quic::default_authority().to_string(),
                path: quic::default_path().to_string(),
                cert_pem: identity.cert_pem.clone(),
                key_pem: identity.key_pem.clone(),
                local_ipv4,
                quiet: false,
                pin_endpoint: true,
                expected_pins: consts::MASQUE_PINS.iter().map(|pin| pin.to_vec()).collect(),
            };
            tokio::spawn(masque_h2::run(h2, internals, None, Some(ready_tx)))
        } else {
            let h3 = quic::TunnelConfig {
                peer,
                sni: consts::CONNECT_SNI.to_string(),
                authority: quic::default_authority().to_string(),
                path: quic::default_path().to_string(),
                cert_pem: identity.cert_pem.clone(),
                key_pem: identity.key_pem.clone(),
                ech_config_list: None,
                noize: noise.clone(),
                local_ipv4,
                quiet: false,
            };
            tokio::spawn(quic::run(h3, internals, None, Some(ready_tx)))
        };

        let ready = tokio::select! {
            ready = ready_rx => ready,
            _ = &mut *stop => {
                tunnel.abort();
                return Ok(());
            }
        };
        if ready.is_err() {
            log::warn!("MASQUE validation failed; reconnecting");
            tunnel.abort();
            if reconnect_pause(stop).await {
                return Ok(());
            }
            continue;
        }
        log::info!("MASQUE data plane is ready");

        match bridge_and_run(
            tun.clone(),
            local_ipv4,
            channels.outbound_tx,
            channels.inbound_rx,
            async move {
                tunnel
                    .await
                    .map_err(|err| AetherError::Other(format!("MASQUE task: {err}")))?
            },
            stop,
        )
        .await?
        {
            BridgeOutcome::Stopped => return Ok(()),
            BridgeOutcome::TunnelEnded => {
                log::warn!("MASQUE tunnel ended; reconnecting");
                if reconnect_pause(stop).await {
                    return Ok(());
                }
            }
        }
    }
}

async fn verify_masque_peer(
    identity: &account::Identity,
    peer: SocketAddr,
    local_ipv4: Ipv4Addr,
    noise: noize::NoizeConfig,
    h2_enabled: bool,
) -> Result<std::time::Duration> {
    if h2_enabled {
        let cfg = masque_h2::H2TunnelConfig {
            peer: masque_h2::h2_peer(peer),
            sni: consts::CONNECT_SNI.to_string(),
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            local_ipv4,
            quiet: true,
            pin_endpoint: true,
            expected_pins: consts::MASQUE_PINS.iter().map(|pin| pin.to_vec()).collect(),
        };
        return masque_h2::verify_h2(&cfg, std::time::Duration::from_secs(6)).await;
    }

    quic::verify_masque(&quic::VerifyParams {
        peer,
        sni: consts::CONNECT_SNI.to_string(),
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: identity.cert_pem.clone(),
        key_pem: identity.key_pem.clone(),
        ech_config_list: None,
        noize: noise,
        timeout: std::time::Duration::from_secs(6),
        local_ipv4,
    })
    .await
}

async fn reconnect_pause(stop: &mut oneshot::Receiver<()>) -> bool {
    tokio::select! {
        _ = tokio::time::sleep(std::time::Duration::from_secs(2)) => false,
        _ = &mut *stop => true,
    }
}

enum BridgeOutcome {
    Stopped,
    TunnelEnded,
}

async fn bridge_and_run<F>(
    tun: Arc<AsyncFd<File>>,
    tunnel_ip: Ipv4Addr,
    outbound: mpsc::Sender<Vec<u8>>,
    inbound: mpsc::Receiver<Vec<u8>>,
    tunnel: F,
    stop: &mut oneshot::Receiver<()>,
) -> Result<BridgeOutcome>
where
    F: std::future::Future<Output = Result<()>>,
{
    log::info!(
        "starting Android TUN packet bridge: app {} <-> tunnel {}",
        VPN_IPV4,
        tunnel_ip,
    );
    let stats = Arc::new(TrafficStats::default());
    let reader = tun_to_tunnel(tun.clone(), tunnel_ip, outbound, stats.clone());
    let writer = tunnel_to_tun(tun, tunnel_ip, inbound, stats.clone());
    let reporter = report_traffic(stats);
    tokio::pin!(reader);
    tokio::pin!(writer);
    tokio::pin!(reporter);
    tokio::pin!(tunnel);

    tokio::select! {
        result = &mut reader => match result {
            Ok(()) => Err(AetherError::Other("Android TUN reader closed".into())),
            Err(error) => Err(error),
        },
        result = &mut writer => match result {
            Ok(()) => Err(AetherError::Other("Android TUN writer closed".into())),
            Err(error) => Err(error),
        },
        result = &mut reporter => match result {
            Ok(()) => Err(AetherError::Other("traffic reporter closed".into())),
            Err(error) => Err(error),
        },
        result = &mut tunnel => {
            if let Err(error) = result {
                log::warn!("tunnel task ended with error: {error}");
            }
            Ok(BridgeOutcome::TunnelEnded)
        },
        _ = &mut *stop => {
            log::info!("packet bridge received stop signal");
            Ok(BridgeOutcome::Stopped)
        },
    }
}

async fn tun_to_tunnel(
    tun: Arc<AsyncFd<File>>,
    tunnel_ip: Ipv4Addr,
    outbound: mpsc::Sender<Vec<u8>>,
    stats: Arc<TrafficStats>,
) -> Result<()> {
    let mut buffer = vec![0u8; 65536];
    let mut first_packet = true;
    loop {
        let mut ready = tun.readable().await?;
        match ready.try_io(|inner| {
            let size = unsafe {
                libc::read(
                    inner.get_ref().as_raw_fd(),
                    buffer.as_mut_ptr().cast(),
                    buffer.len(),
                )
            };
            if size >= 0 {
                Ok(size as usize)
            } else {
                Err(std::io::Error::last_os_error())
            }
        }) {
            Ok(Ok(0)) => return Ok(()),
            Ok(Ok(size)) => {
                let mut packet = buffer[..size].to_vec();
                let translated = translate_ipv4(&mut packet, VPN_IPV4, tunnel_ip, true);
                if first_packet {
                    log::info!(
                        "first TUN -> tunnel packet: {}; source_rewritten={translated}",
                        packet_summary(&packet),
                    );
                    first_packet = false;
                }
                stats.outbound_packets.fetch_add(1, Ordering::Relaxed);
                stats
                    .outbound_bytes
                    .fetch_add(size as u64, Ordering::Relaxed);
                outbound
                    .send(packet)
                    .await
                    .map_err(|_| AetherError::Other("tunnel outbound channel closed".into()))?;
            }
            Ok(Err(err)) => return Err(AetherError::Io(err)),
            Err(_) => continue,
        }
    }
}

async fn tunnel_to_tun(
    tun: Arc<AsyncFd<File>>,
    tunnel_ip: Ipv4Addr,
    mut inbound: mpsc::Receiver<Vec<u8>>,
    stats: Arc<TrafficStats>,
) -> Result<()> {
    let mut first_packet = true;
    while let Some(mut packet) = inbound.recv().await {
        let translated = translate_ipv4(&mut packet, tunnel_ip, VPN_IPV4, false);
        if first_packet {
            log::info!(
                "first tunnel -> TUN packet: {}; destination_rewritten={translated}",
                packet_summary(&packet),
            );
            first_packet = false;
        }
        stats.inbound_packets.fetch_add(1, Ordering::Relaxed);
        stats
            .inbound_bytes
            .fetch_add(packet.len() as u64, Ordering::Relaxed);
        let mut offset = 0;
        while offset < packet.len() {
            let mut ready = tun.writable().await?;
            match ready.try_io(|inner| {
                let size = unsafe {
                    libc::write(
                        inner.get_ref().as_raw_fd(),
                        packet[offset..].as_ptr().cast(),
                        packet.len() - offset,
                    )
                };
                if size >= 0 {
                    Ok(size as usize)
                } else {
                    Err(std::io::Error::last_os_error())
                }
            }) {
                Ok(Ok(0)) => return Err(AetherError::Other("zero-byte TUN write".into())),
                Ok(Ok(size)) => offset += size,
                Ok(Err(err)) => return Err(AetherError::Io(err)),
                Err(_) => continue,
            }
        }
    }
    Ok(())
}

async fn report_traffic(stats: Arc<TrafficStats>) -> Result<()> {
    let mut interval = tokio::time::interval(std::time::Duration::from_secs(2));
    let mut previous = (0, 0, 0, 0);
    let mut idle_ticks = 0u32;
    interval.tick().await;
    loop {
        interval.tick().await;
        let current = (
            stats.outbound_packets.load(Ordering::Relaxed),
            stats.outbound_bytes.load(Ordering::Relaxed),
            stats.inbound_packets.load(Ordering::Relaxed),
            stats.inbound_bytes.load(Ordering::Relaxed),
        );
        if current != previous {
            log::info!(
                "traffic: TUN -> tunnel {} packets / {} bytes; tunnel -> TUN {} packets / {} bytes",
                current.0,
                current.1,
                current.2,
                current.3,
            );
            previous = current;
            idle_ticks = 0;
        } else {
            idle_ticks += 1;
            if idle_ticks == 5 {
                log::warn!("traffic idle: Android has not delivered any new IPv4 packets for 10s");
            }
        }
    }
}

fn packet_summary(packet: &[u8]) -> String {
    if packet.len() < 20 || packet[0] >> 4 != 4 {
        return format!("non-IPv4, {} bytes", packet.len());
    }
    let source = Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]);
    let destination = Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]);
    format!(
        "{source} -> {destination}, proto={}, {} bytes",
        packet[9],
        packet.len()
    )
}

fn translate_ipv4(packet: &mut [u8], old: Ipv4Addr, new: Ipv4Addr, source: bool) -> bool {
    if packet.len() < 20 || packet[0] >> 4 != 4 {
        return false;
    }
    let ihl = ((packet[0] & 0x0f) as usize) * 4;
    if ihl < 20 || packet.len() < ihl {
        return false;
    }
    let address_offset = if source { 12 } else { 16 };
    if packet[address_offset..address_offset + 4] != old.octets() {
        return false;
    }
    packet[address_offset..address_offset + 4].copy_from_slice(&new.octets());

    let fragment = u16::from_be_bytes([packet[6], packet[7]]);
    if fragment & 0x1fff == 0 {
        let checksum_offset = match packet[9] {
            6 if packet.len() >= ihl + 18 => Some(ihl + 16),
            17 if packet.len() >= ihl + 8 => Some(ihl + 6),
            _ => None,
        };
        if let Some(offset) = checksum_offset {
            let checksum = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
            if checksum != 0 {
                let updated = update_checksum(checksum, old.octets(), new.octets());
                packet[offset..offset + 2].copy_from_slice(&updated.to_be_bytes());
            }
        }
    }

    packet[10] = 0;
    packet[11] = 0;
    let checksum = internet_checksum(&packet[..ihl]);
    packet[10..12].copy_from_slice(&checksum.to_be_bytes());
    true
}

fn update_checksum(checksum: u16, old: [u8; 4], new: [u8; 4]) -> u16 {
    let mut sum = (!checksum as u32)
        + (!u16::from_be_bytes([old[0], old[1]]) as u32 & 0xffff)
        + u16::from_be_bytes([new[0], new[1]]) as u32
        + (!u16::from_be_bytes([old[2], old[3]]) as u32 & 0xffff)
        + u16::from_be_bytes([new[2], new[3]]) as u32;
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

fn internet_checksum(data: &[u8]) -> u16 {
    let mut sum = 0u32;
    for chunk in data.chunks(2) {
        sum += if chunk.len() == 2 {
            u16::from_be_bytes([chunk[0], chunk[1]]) as u32
        } else {
            (chunk[0] as u32) << 8
        };
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checksum_translation_is_reversible() {
        let mut packet = vec![
            0x45, 0, 0, 40, 0, 0, 0x40, 0, 64, 6, 0, 0, 172, 31, 19, 2, 1, 1, 1, 1,
        ];
        packet.extend_from_slice(&[
            0x12, 0x34, 0, 80, 0, 0, 0, 0, 0, 0, 0, 0, 0x50, 2, 0xff, 0xff, 0x44, 0x55, 0, 0,
        ]);
        let header = internet_checksum(&packet[..20]);
        packet[10..12].copy_from_slice(&header.to_be_bytes());
        let original_tcp = u16::from_be_bytes([packet[36], packet[37]]);

        let assigned = Ipv4Addr::new(100, 96, 0, 2);
        translate_ipv4(&mut packet, VPN_IPV4, assigned, true);
        translate_ipv4(&mut packet, assigned, VPN_IPV4, true);

        assert_eq!(&packet[12..16], &VPN_IPV4.octets());
        assert_eq!(u16::from_be_bytes([packet[36], packet[37]]), original_tcp);
        assert_eq!(internet_checksum(&packet[..20]), 0);
    }
}
