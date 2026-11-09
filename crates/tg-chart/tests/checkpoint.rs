//! Long reads call the caller's checkpoint every 4096 rows (the Kotlin `long reads stop at the caller's checkpoint`).

use tg_chart::{Aggregate, ChartColumn, ChartData, ChartSpec, ChartType, ColumnProfiler, Measure};
use tg_sql::model::jdbc_types::INTEGER;

#[test]
fn checkpoints_are_called_every_4096_rows() {
    let rows: Vec<Vec<Option<String>>> = (0..20_000)
        .map(|i| vec![Some(i.to_string()), Some(i.to_string())])
        .collect();
    let columns = [
        ChartColumn::new("x", "", INTEGER),
        ChartColumn::new("y", "", INTEGER),
    ];
    let mut calls = 0;
    let profiles = ColumnProfiler::profile_with(&columns, &rows, &mut || calls += 1);
    // Once per column, five times each for 20 000 rows.
    assert_eq!(calls, 2 * 5);

    let spec = ChartSpec::new(
        ChartType::Scatter,
        "x",
        vec![Measure::new("y", Aggregate::None)],
    );
    let mut calls = 0;
    ChartData::build_with(&rows, &profiles, &spec, &mut || calls += 1);
    assert_eq!(calls, 5);
}
