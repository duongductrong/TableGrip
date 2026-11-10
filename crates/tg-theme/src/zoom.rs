//! Zoom of the whole interface, in the steps browsers and VS Code use, as the Kotlin `UiZoom`. It multiplies the
//! density, so every dp, font size and image grows together.

/// 100 %.
pub const DEFAULT: i32 = 100;

/// The 13 levels, in percent.
pub const LEVELS: [i32; 13] = [50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300];

/// The level closest to [percent], for values typed into settings by hand; the lower level wins a tie.
pub fn snap(percent: i32) -> i32 {
    // `min_by_key` keeps the first of equal keys, as Kotlin's `minBy` does.
    *LEVELS
        .iter()
        .min_by_key(|&&l| (l - percent).abs())
        .expect("LEVELS is not empty")
}

/// The next level above (or, for a negative [direction], below) [percent], stopping at the ends.
pub fn step(percent: i32, direction: i32) -> i32 {
    if direction > 0 {
        LEVELS
            .iter()
            .copied()
            .find(|&l| l > percent)
            .unwrap_or(LEVELS[LEVELS.len() - 1])
    } else {
        LEVELS
            .iter()
            .rev()
            .copied()
            .find(|&l| l < percent)
            .unwrap_or(LEVELS[0])
    }
}

/// The density factor for [percent] (snapped), to multiply the window's own scale by.
pub fn factor(percent: i32) -> f32 {
    snap(percent) as f32 / 100.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snaps_to_the_nearest_level() {
        assert_eq!(snap(100), 100);
        assert_eq!(snap(104), 100);
        assert_eq!(snap(105), 100); // tie between 100 and 110: the first wins
        assert_eq!(snap(106), 110);
        assert_eq!(snap(10), 50);
        assert_eq!(snap(1000), 300);
        assert_eq!(snap(71), 67);
    }

    #[test]
    fn steps_stop_at_the_ends() {
        assert_eq!(step(100, 1), 110);
        assert_eq!(step(100, -1), 90);
        assert_eq!(step(300, 1), 300);
        assert_eq!(step(50, -1), 50);
        assert_eq!(step(104, 1), 110);
        assert_eq!(step(104, -1), 100);
    }

    #[test]
    fn factor_multiplies_density() {
        assert_eq!(factor(125), 1.25);
        assert_eq!(factor(0), 0.5);
    }
}
