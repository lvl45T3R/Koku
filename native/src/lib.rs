#![recursion_limit = "512"]
use aether::error::{AetherError, Result};
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong};
use jni::{JNIEnv, JavaVM};
use serde::Deserialize;
use std::collections::HashMap;
use std::ffi::CString;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, Once, OnceLock};
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::oneshot;

static LOG_INIT: Once = Once::new();
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static ACTIVE: AtomicBool = AtomicBool::new(false);
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
        metadata.level() <= log::max_level()
    }
    fn log(&self, record: &log::Record<'_>) {
        if self.enabled(record.metadata()) {
            let message = record.args().to_string();
            write_android_log(record.level(), &message);
            write_ui_log(record.level(), &message);
        }
    }
    fn flush(&self) {}
}
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AndroidConfig {
    protocol: String,
    scan_mode: String,
    noize_profile: String,
    no_ir_exit: bool,
    config_dir: String,
    #[serde(default)]
    options: HashMap<String, String>,
    #[serde(default)]
    psiphon_bin: String,
}
fn runs() -> &'static Mutex<HashMap<i64, oneshot::Sender<()>>> {
    RUNS.get_or_init(|| Mutex::new(HashMap::new()))
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
    let json: String = match env.get_string(&config_json) {
        Ok(v) => v.into(),
        Err(_) => {
            close_fd(tun_fd);
            return 0;
        }
    };
    let cfg: AndroidConfig = match serde_json::from_str(&json) {
        Ok(v) => v,
        Err(e) => {
            log::error!("Invalid config: {e}");
            close_fd(tun_fd);
            return 0;
        }
    };
    if cfg.config_dir.is_empty()
        || ACTIVE
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
    {
        close_fd(tun_fd);
        log::error!("Native engine is still stopping or config is invalid");
        return 0;
    }
    if let Err(e) = set_log_target(&mut env, log_sink) {
        log::error!("Logger: {e}");
    }
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    let (tx, rx) = oneshot::channel();
    runs().lock().unwrap().insert(handle, tx);
    match std::thread::Builder::new()
        .name("aether-native".into())
        .stack_size(16 * 1024 * 1024)
        .spawn(move || {
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .worker_threads(3)
                .thread_stack_size(8 * 1024 * 1024)
                .build();
            match runtime {
                Ok(rt) => {
                    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        rt.block_on(run_android(cfg, tun_fd, rx))
                    }));
                    match result {
                        Ok(Ok(())) => log::info!("Aether engine stopped cleanly"),
                        Ok(Err(e)) => log::error!("Aether engine stopped with error: {e}"),
                        Err(_) => log::error!("Aether engine panicked"),
                    }
                    drop(rt);
                }
                Err(e) => {
                    close_fd(tun_fd);
                    log::error!("Runtime: {e}");
                }
            }
            runs().lock().unwrap().remove(&handle);
            ACTIVE.store(false, Ordering::SeqCst);
            log::info!("native worker finished");
        }) {
        Ok(_) => handle,
        Err(e) => {
            runs().lock().unwrap().remove(&handle);
            ACTIVE.store(false, Ordering::SeqCst);
            close_fd(tun_fd);
            log::error!("Native thread: {e}");
            0
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_io_github_lvl45t3r_koku_AetherNative_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(tx) = runs().lock().unwrap().remove(&handle) {
        let _ = tx.send(());
    }
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

mod settings;
mod packet;
use settings::OPTIONS;
#[derive(Default)]
struct Traffic {
    out_packets: AtomicU64,
    out_bytes: AtomicU64,
    in_packets: AtomicU64,
    in_bytes: AtomicU64,
    out_tcp_payload: AtomicU64,
    in_tcp_payload: AtomicU64,
}
struct CountingDevice {
    inner: tun::AsyncDevice,
    stats: Arc<Traffic>,
}
impl AsyncRead for CountingDevice {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        let before = buf.filled().len();
        let result = Pin::new(&mut self.inner).poll_read(cx, buf);
        if matches!(result, Poll::Ready(Ok(()))) {
            let n = buf.filled().len() - before;
            if n > 0 {
                self.stats.out_packets.fetch_add(1, Ordering::Relaxed);
                self.stats.out_bytes.fetch_add(n as u64, Ordering::Relaxed);
                self.stats.out_tcp_payload.fetch_add(packet::tcp_payload_len(&buf.filled()[before..]) as u64, Ordering::Relaxed);
            }
        }
        result
    }
}
impl AsyncWrite for CountingDevice {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        let result = Pin::new(&mut self.inner).poll_write(cx, data);
        if let Poll::Ready(Ok(n)) = result {
            if n > 0 {
                self.stats.in_packets.fetch_add(1, Ordering::Relaxed);
                self.stats.in_bytes.fetch_add(n as u64, Ordering::Relaxed);
                self.stats.in_tcp_payload.fetch_add(packet::tcp_payload_len(&data[..n]) as u64, Ordering::Relaxed);
            }
        }
        result
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}
fn configure(cfg: &AndroidConfig) -> Result<String> {
    // The worker is exclusive: no stale settings or credentials cross into the next session.
    for (key, _) in std::env::vars().filter(|(key, _)| key.starts_with("AETHER_")) {
        std::env::remove_var(key);
    }
    for (key, value) in &cfg.options {
        if !OPTIONS.contains(&key.as_str()) {
            return Err(AetherError::Other(format!("Unsupported setting: {key}")));
        }
        if !value.trim().is_empty() {
            std::env::set_var(key, value.trim());
        }
    }
    let (protocol, h2, classic) = settings::transport(&cfg.protocol)
        .ok_or_else(|| AetherError::Other("Unknown tunnel protocol".into()))?;
    std::env::set_var("AETHER_PROTOCOL", protocol);
    std::env::set_var("AETHER_MASQUE_HTTP2", if h2 { "1" } else { "0" });
    std::env::set_var(
        "AETHER_GOOL_MODE",
        if classic { "classic" } else { "masque" },
    );
    std::env::set_var("AETHER_SCAN", &cfg.scan_mode);
    if !cfg.options.contains_key("AETHER_NOIZE") {
        std::env::set_var(
            "AETHER_NOIZE",
            if cfg.noize_profile == "balanced" && protocol != "wg" && !classic {
                "firewall"
            } else {
                &cfg.noize_profile
            },
        );
    }
    if !cfg.options.contains_key("AETHER_IP") {
        std::env::set_var("AETHER_IP", "v4");
    }
    if !cfg.options.contains_key("AETHER_QUICK_RECONNECT") {
        std::env::set_var("AETHER_QUICK_RECONNECT", "1");
    }
    std::env::set_var(
        "AETHER_CONFIG",
        format!("{}/aether-wireguard.toml", cfg.config_dir),
    );
    std::env::set_var(
        "AETHER_MASQUE_CONFIG",
        format!("{}/aether-masque.toml", cfg.config_dir),
    );
    std::env::set_var("AETHER_SOCKS", "127.0.0.1:1819");
    std::env::set_var("AETHER_HTTP_PROXY", "127.0.0.1:1830");
    std::env::set_var("AETHER_TOR_BIND", "127.0.0.1:1820");
    std::env::set_var("AETHER_TOR_HTTP", "127.0.0.1:1831");
    std::env::set_var("AETHER_PSIPHON_BIND", "127.0.0.1:1821");
    std::env::set_var("AETHER_PSIPHON_HTTP", "127.0.0.1:1832");
    if !cfg.psiphon_bin.is_empty() {
        std::env::set_var("AETHER_PSIPHON_BIN", &cfg.psiphon_bin);
    }
    if !cfg.options.contains_key("AETHER_TOR_PT") {
        if let Some(directory) = std::path::Path::new(&cfg.psiphon_bin).parent() {
            let helper = directory.join("liblyrebird.so");
            if helper.is_file() {
                let transports = [
                    "obfs4",
                    "obfs3",
                    "scramblesuit",
                    "meek_lite",
                    "snowflake",
                    "webtunnel",
                ]
                .map(|name| format!("{name}={}", helper.display()))
                .join(";");
                std::env::set_var("AETHER_TOR_PT", transports);
            }
        }
    }
    if cfg.no_ir_exit {
        std::env::set_var("AETHER_EXIT_LOC", "!IR");
    }
    let tor = aether::tor::mode();
    let psiphon = aether::psiphon::mode();
    if tor != aether::tor::Mode::Off && psiphon != aether::psiphon::Mode::Off {
        return Err(AetherError::Other(
            "Choose Tor or Psiphon as the final carrier".into(),
        ));
    }
    Ok(if tor == aether::tor::Mode::Chain {
        "127.0.0.1:1820"
    } else if psiphon == aether::psiphon::Mode::Chain {
        "127.0.0.1:1821"
    } else {
        "127.0.0.1:1819"
    }
    .into())
}
async fn wait_proxy(address: &str) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    loop {
        let ready = tokio::time::timeout(std::time::Duration::from_secs(2), async {
            let mut stream = tokio::net::TcpStream::connect(address).await?;
            stream.write_all(&[5, 1, 0]).await?;
            let mut reply = [0; 2];
            stream.read_exact(&mut reply).await?;
            Ok::<bool, std::io::Error>(reply == [5, 0])
        })
        .await;
        if matches!(ready, Ok(Ok(true))) {
            return;
        }
        tokio::time::sleep(std::time::Duration::from_millis(300)).await;
    }
}
async fn run_android(
    cfg: AndroidConfig,
    tun_fd: i32,
    mut stop: oneshot::Receiver<()>,
) -> Result<()> {
    // Adopt the detached fd immediately; every error path closes it.
    use std::os::fd::{FromRawFd, IntoRawFd};
    let owned = unsafe { std::os::fd::OwnedFd::from_raw_fd(tun_fd) };
    std::fs::create_dir_all(&cfg.config_dir)?;
    // An organization token from an earlier VPN session must not sign in another team.
    aether::zerotrust::clear_token().await;
    let proxy = configure(&cfg)?;
    let level = cfg
        .options
        .get("AETHER_LOG_LEVEL")
        .and_then(|value| value.parse::<log::LevelFilter>().ok())
        .unwrap_or(log::LevelFilter::Info);
    // Android lifecycle/statistics events must remain available at every verbosity.
    log::set_max_level(level.max(log::LevelFilter::Info));
    aether::socks::reset_session_settings();
    log::info!(
        "native build uses vendored Koku engine snapshot {}",
        env!("KOKU_ENGINE_SOURCE")
    );
    let mut tc = tun::Configuration::default();
    tc.raw_fd(owned.into_raw_fd())
        .close_fd_on_drop(true)
        .mtu(1280);
    let device = tun::create_as_async(&tc).map_err(|e| AetherError::Other(e.to_string()))?;
    let core = aether::run_with(Vec::new());
    tokio::pin!(core);
    tokio::select! {
        result = &mut core => return result,
        _ = &mut stop => return Ok(()),
        _ = wait_proxy(&proxy) => {},
    }
    if cfg.no_ir_exit {
        let exit = tokio::select! {
            result = &mut core => return result,
            _ = &mut stop => return Ok(()),
            exit = aether::exitloc::report_through_socks(proxy.parse().unwrap(), "Android final proxy") => exit,
        };
        match exit {
            Some(ref exit) if exit.country.as_deref().is_some_and(|c| c != "IR") => {}
            _ => {
                return Err(AetherError::Other(
                    "NO-IR Exit: final exit is Iranian or unverifiable".into(),
                ))
            }
        }
    }
    let stats = Arc::new(Traffic::default());
    let counted = CountingDevice {
        inner: device,
        stats: stats.clone(),
    };
    let mut args = tun2proxy::Args::default();
    args.proxy = tun2proxy::ProxyParameters::try_from(format!("socks5://{proxy}").as_str())
        .map_err(|e| AetherError::Other(e.to_string()))?;
    args.dns = tun2proxy::ArgDns::OverTcp;
    args.setup = false;
    args.ipv6_enabled = true;
    args.tcp_mss = Some(1240);
    let cancel = tun2proxy::CancellationToken::new();
    let bridge = tun2proxy::run(counted, 1280, args, cancel.clone());
    tokio::pin!(bridge);
    log::info!("starting Android TUN packet bridge through {proxy}");
    let guard = watch_final_proxy(proxy.clone(), cfg.no_ir_exit);
    tokio::pin!(guard);
    let mut tick = tokio::time::interval(std::time::Duration::from_secs(2));
    loop {
        tokio::select! {
            _ = &mut stop => { cancel.cancel(); let _ = tokio::time::timeout(std::time::Duration::from_secs(3), &mut bridge).await; return Ok(()); },
            result = &mut core => return result,
            reason = &mut guard => return Err(reason),
            result = &mut bridge => return result.map(|_| ()).map_err(|e| AetherError::Other(e.to_string())),
            _ = tick.tick() => log::info!("traffic: TUN -> tunnel {} packets / {} bytes; tunnel -> TUN {} packets / {} bytes; TCP payload: out {} / in {} bytes", stats.out_packets.load(Ordering::Relaxed), stats.out_bytes.load(Ordering::Relaxed), stats.in_packets.load(Ordering::Relaxed), stats.in_bytes.load(Ordering::Relaxed), stats.out_tcp_payload.load(Ordering::Relaxed), stats.in_tcp_payload.load(Ordering::Relaxed)),
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn managed_paths_and_listeners_cannot_be_overridden() {
        for key in [
            "AETHER_CONFIG",
            "AETHER_SOCKS",
            "AETHER_MARK",
            "AETHER_REGISTER",
        ] {
            assert!(!OPTIONS.contains(&key));
        }
        for key in [
            "AETHER_ROUTE_BLOCK",
            "AETHER_UPSTREAM",
            "AETHER_ECH",
            "AETHER_TOR",
        ] {
            assert!(OPTIONS.contains(&key));
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_io_github_lvl45t3r_koku_AetherNative_nativeTeam(
    mut env: JNIEnv,
    _class: JClass,
    action: JString,
    payload: JString,
) -> jni::sys::jstring {
    use std::ffi::CStr;
    let reply = (|| -> std::result::Result<String, String> {
        let action: String = env.get_string(&action).map_err(|e| e.to_string())?.into();
        let raw: String = env.get_string(&payload).map_err(|e| e.to_string())?.into();
        let body: serde_json::Value = serde_json::from_str(&raw).map_err(|e| e.to_string())?;
        let id = body.get("id").and_then(|v| v.as_u64()).unwrap_or_default();
        let cbody = CString::new(raw).map_err(|e| e.to_string())?;
        let ptr = unsafe {
            match action.as_str() {
                "request" if !ACTIVE.load(Ordering::SeqCst) => {
                    aether::ffi::aether_team_code_request(cbody.as_ptr())
                }
                "resend" if !ACTIVE.load(Ordering::SeqCst) => {
                    aether::ffi::aether_team_code_resend(id)
                }
                "submit" if !ACTIVE.load(Ordering::SeqCst) => {
                    let code = CString::new(
                        body.get("code")
                            .and_then(|v| v.as_str())
                            .unwrap_or_default(),
                    )
                    .map_err(|e| e.to_string())?;
                    aether::ffi::aether_team_code_submit(id, code.as_ptr())
                }
                "poll" => aether::ffi::aether_job_poll(id),
                "free" => aether::ffi::aether_job_free(id),
                "sessionFree" => aether::ffi::aether_team_session_free(id),
                _ => return Err("Disconnect before signing in, or choose a valid action".into()),
            }
        };
        if ptr.is_null() {
            return Err("Core returned no reply".into());
        }
        let reply = unsafe { CStr::from_ptr(ptr).to_string_lossy().into_owned() };
        unsafe {
            aether::ffi::aether_string_free(ptr);
        }
        Ok(reply)
    })()
    .unwrap_or_else(|e| serde_json::json!({"ok": false, "error": e}).to_string());
    env.new_string(reply)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
async fn watch_final_proxy(proxy: String, enabled: bool) -> AetherError {
    if !enabled {
        if aether::exitloc::report_through_socks(proxy.parse().unwrap(), "Android final proxy")
            .await.is_none() {
            log::warn!("Final proxy exit verification unavailable");
        }
        return std::future::pending().await;
    }
    loop {
        tokio::time::sleep(std::time::Duration::from_secs(60)).await;
        match aether::exitloc::report_through_socks(proxy.parse().unwrap(), "Android final proxy")
            .await
        {
            Some(exit) if exit.country.as_deref().is_some_and(|c| c != "IR") => {}
            _ => {
                return AetherError::Other(
                    "NO-IR Exit: final exit changed or became unverifiable".into(),
                )
            }
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_io_github_lvl45t3r_koku_AetherNative_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jni::sys::jboolean {
    u8::from(ACTIVE.load(Ordering::SeqCst))
}
