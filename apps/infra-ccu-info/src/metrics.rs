use prometheus::{ labels, opts, register_int_gauge, IntGauge };
use crate::{ model::{ Config, Metric } };

pub fn metrics_init(config: Config) -> Metric {
    let ccu_template: IntGauge = register_int_gauge!(
        opts!(
            "infra_ccu_setup",
            "The environment setup based on ccu template",
            labels! {
                "environment" => &config.env
            }
        )
    ).unwrap();
    let metrics = Metric { ccu_template };
    return metrics;
}
