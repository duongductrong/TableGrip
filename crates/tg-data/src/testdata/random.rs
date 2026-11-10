//! `java.util.SplittableRandom` (JDK 17+), so a seed gives the same test data as the Kotlin build.

const GOLDEN_GAMMA: u64 = 0x9e37_79b9_7f4a_7c15;

pub struct SplittableRandom {
    seed: u64,
}

fn mix64(z: u64) -> u64 {
    let z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    let z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    z ^ (z >> 31)
}

fn mix32(z: u64) -> i32 {
    let z = (z ^ (z >> 33)).wrapping_mul(0x62a9_d9ed_7997_05f5);
    ((z ^ (z >> 28)).wrapping_mul(0xcb24_d0a5_c88c_35b3) >> 32) as i32
}

impl SplittableRandom {
    pub fn new(seed: i64) -> Self {
        Self { seed: seed as u64 }
    }

    fn next_seed(&mut self) -> u64 {
        self.seed = self.seed.wrapping_add(GOLDEN_GAMMA);
        self.seed
    }

    pub fn next_int(&mut self) -> i32 {
        let s = self.next_seed();
        mix32(s)
    }

    pub fn next_long(&mut self) -> i64 {
        let s = self.next_seed();
        mix64(s) as i64
    }

    /// In `0..bound`.
    pub fn next_int_bound(&mut self, bound: i32) -> i32 {
        let mut r = self.next_int();
        let m = bound.wrapping_sub(1);
        if bound & m == 0 {
            return r & m;
        }
        let mut u = ((r as u32) >> 1) as i32;
        loop {
            r = u % bound;
            if u.wrapping_add(m).wrapping_sub(r) >= 0 {
                return r;
            }
            u = ((self.next_int() as u32) >> 1) as i32;
        }
    }

    /// In `origin..bound`.
    pub fn next_int_range(&mut self, origin: i32, bound: i32) -> i32 {
        let mut r = self.next_int();
        if origin < bound {
            let n = bound.wrapping_sub(origin);
            let m = n.wrapping_sub(1);
            if n & m == 0 {
                r = (r & m).wrapping_add(origin);
            } else if n > 0 {
                let mut u = ((r as u32) >> 1) as i32;
                loop {
                    r = u % n;
                    if u.wrapping_add(m).wrapping_sub(r) >= 0 {
                        break;
                    }
                    u = ((self.next_int() as u32) >> 1) as i32;
                }
                r = r.wrapping_add(origin);
            } else {
                while r < origin || r >= bound {
                    r = self.next_int();
                }
            }
        }
        r
    }

    /// In `0..bound`.
    pub fn next_long_bound(&mut self, bound: i64) -> i64 {
        let mut r = self.next_long();
        let m = bound.wrapping_sub(1);
        if bound & m == 0 {
            return r & m;
        }
        let mut u = ((r as u64) >> 1) as i64;
        loop {
            r = u % bound;
            if u.wrapping_add(m).wrapping_sub(r) >= 0 {
                return r;
            }
            u = ((self.next_long() as u64) >> 1) as i64;
        }
    }

    /// In `origin..bound`.
    pub fn next_long_range(&mut self, origin: i64, bound: i64) -> i64 {
        let mut r = self.next_long();
        if origin < bound {
            let n = bound.wrapping_sub(origin);
            let m = n.wrapping_sub(1);
            if n & m == 0 {
                r = (r & m).wrapping_add(origin);
            } else if n > 0 {
                let mut u = ((r as u64) >> 1) as i64;
                loop {
                    r = u % n;
                    if u.wrapping_add(m).wrapping_sub(r) >= 0 {
                        break;
                    }
                    u = ((self.next_long() as u64) >> 1) as i64;
                }
                r = r.wrapping_add(origin);
            } else {
                while r < origin || r >= bound {
                    r = self.next_long();
                }
            }
        }
        r
    }

    pub fn next_double(&mut self) -> f64 {
        ((self.next_long() as u64) >> 11) as f64 * (1.0 / (1u64 << 53) as f64)
    }

    pub fn next_boolean(&mut self) -> bool {
        self.next_int() < 0
    }

    pub fn pick<'a, T>(&mut self, list: &'a [T]) -> &'a T {
        &list[self.next_int_bound(list.len() as i32) as usize]
    }
}

/// Java's `String.hashCode`.
pub fn java_hash(text: &str) -> i32 {
    text.encode_utf16()
        .fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(i32::from(c)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_java() {
        // SplitMix64's first output for seed 0, which is SplittableRandom(0).nextLong().
        let mut r = SplittableRandom::new(0);
        assert_eq!(r.next_long(), -2152535657050944081);
        assert_eq!(java_hash("email"), 96619420);
    }
}
