use serde::{Deserialize, Serialize};
use std::cmp::Ordering;

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct Clock {
    pub counter: u64,
    pub device: String,
}

impl Ord for Clock {
    fn cmp(&self, other: &Self) -> Ordering {
        self.counter
            .cmp(&other.counter)
            .then_with(|| self.device.cmp(&other.device))
    }
}

impl PartialOrd for Clock {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct EventRecord {
    pub id: String,
    pub clock: Clock,
    #[serde(default)]
    pub deleted: bool,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub event_date: String,
    #[serde(default)]
    pub r#type: String,
    #[serde(default = "default_visibility")]
    pub visibility_days: i32,
    #[serde(default = "default_repeat")]
    pub repeat_rule: String,
    pub event_time: Option<String>,
    #[serde(default)]
    pub completed: bool,
    pub completed_through: Option<String>,
    #[serde(default = "default_date_system")]
    pub date_system: String,
    #[serde(default)]
    pub lunar_month: i32,
    #[serde(default)]
    pub lunar_day: i32,
    #[serde(default)]
    pub lunar_leap: bool,
    pub planned_start: Option<String>,
    pub planned_end: Option<String>,
}

fn default_visibility() -> i32 { -1 }
fn default_repeat() -> String { "NONE".into() }
fn default_date_system() -> String { "SOLAR".into() }

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Snapshot {
    pub schema: u32,
    pub updated_by: String,
    #[serde(default)]
    pub events: Vec<EventRecord>,
}

impl Snapshot {
    pub fn empty(device: impl Into<String>) -> Self {
        Self { schema: 1, updated_by: device.into(), events: Vec::new() }
    }
}
