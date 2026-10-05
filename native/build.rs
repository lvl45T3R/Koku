use std::fs;

fn main() {
    println!("cargo:rustc-link-arg=-Wl,-z,max-page-size=16384");
    if std::env::var("TARGET").unwrap_or_default().contains("android") {
        // BoringSSL uses the NDK static C++ runtime; its ABI support is separate.
        println!("cargo:rustc-link-lib=c++abi");
    }
    println!("cargo:rerun-if-changed=../engine/REVISION");
    println!("cargo:rerun-if-changed=../engine/aether/src");
    println!("cargo:rerun-if-changed=../engine/quiche/quiche/src");
    println!("cargo:rerun-if-changed=../engine/quiche/octets/src");
    println!("cargo:rerun-if-changed=../engine/quiche/qlog/src");

    let source = fs::read_to_string("../engine/REVISION")
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "unrecorded".to_owned());
    println!("cargo:rustc-env=KOKU_ENGINE_SOURCE={source}");
}
