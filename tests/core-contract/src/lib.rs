#![allow(dead_code)]
#[path = "../../../engine/aether/src/routing.rs"]
mod routing;
#[path = "../../../engine/aether/src/sniff.rs"]
mod sniff;
#[path = "../../../native/src/settings.rs"]
mod settings;
#[path = "../../../native/src/packet.rs"]
mod packet;
#[path = "../../../engine/aether/src/sysprofile.rs"]
mod sysprofile;
#[test]
fn resource_profiles_can_change_between_sessions() {
    std::env::set_var("AETHER_PERF_PROFILE", "low");
    assert_eq!(sysprofile::tuning().tier, sysprofile::Tier::Low);
    std::env::set_var("AETHER_PERF_PROFILE", "high");
    assert_eq!(sysprofile::tuning().tier, sysprofile::Tier::High);
    std::env::set_var("AETHER_PERF_PROFILE", "medium");
    assert_eq!(sysprofile::tuning().tier, sysprofile::Tier::Medium);
    std::env::remove_var("AETHER_PERF_PROFILE");
}
