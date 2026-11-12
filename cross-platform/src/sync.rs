use crate::model::{EventRecord, Snapshot};
use std::collections::BTreeMap;

pub fn merge(local: &Snapshot, remote: &Snapshot, device_id: &str) -> Snapshot {
    let mut merged: BTreeMap<String, EventRecord> = BTreeMap::new();

    for item in local.events.iter().chain(remote.events.iter()) {
        match merged.get(&item.id) {
            None => { merged.insert(item.id.clone(), item.clone()); }
            Some(current) if item.clock > current.clock => {
                merged.insert(item.id.clone(), item.clone());
            }
            _ => {}
        }
    }

    Snapshot {
        schema: 1,
        updated_by: device_id.to_owned(),
        events: merged.into_values().collect(),
    }
}

pub fn next_counter(snapshot: &Snapshot, local_counter: u64) -> u64 {
    let observed = snapshot.events.iter().map(|e| e.clock.counter).max().unwrap_or(0);
    observed.max(local_counter).saturating_add(1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Clock, EventRecord};

    fn item(id: &str, counter: u64, device: &str, title: &str) -> EventRecord {
        EventRecord {
            id: id.into(),
            clock: Clock { counter, device: device.into() },
            deleted: false,
            title: title.into(),
            event_date: "2026-09-18".into(),
            r#type: "待办".into(),
            visibility_days: -1,
            repeat_rule: "NONE".into(),
            event_time: None,
            completed: false,
            completed_through: None,
            date_system: "SOLAR".into(),
            lunar_month: 0,
            lunar_day: 0,
            lunar_leap: false,
            planned_start: None,
            planned_end: None,
        }
    }

    #[test]
    fn higher_clock_wins() {
        let mut a = Snapshot::empty("A");
        let mut b = Snapshot::empty("B");
        a.events.push(item("1", 2, "A", "old"));
        b.events.push(item("1", 3, "B", "new"));
        assert_eq!(merge(&a, &b, "C").events[0].title, "new");
    }

    #[test]
    fn equal_counter_is_deterministic() {
        let mut a = Snapshot::empty("A");
        let mut b = Snapshot::empty("B");
        a.events.push(item("1", 3, "A", "a"));
        b.events.push(item("1", 3, "B", "b"));
        assert_eq!(merge(&a, &b, "C").events[0].title, "b");
    }
}
