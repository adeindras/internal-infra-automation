use structopt::StructOpt;
use prometheus::{ IntGauge };

#[derive(StructOpt, Debug, Clone)]
#[structopt(name = "env")]
pub struct Config {
    /// CCU Number
    #[structopt(long, env = "CCU")]
    pub ccu: i64,

    /// Environment Name
    #[structopt(long, env = "ENV")]
    pub env: String,
}

#[derive(Debug, Clone)]
pub struct Metric {
    pub ccu_template: IntGauge,
}

#[derive(Debug, Clone)]
pub struct AppState {
    pub config: Config,
    pub metric: Metric,
}
