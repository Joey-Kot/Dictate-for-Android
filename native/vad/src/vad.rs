//! A fresh detector per input; streaming 16 kHz mono signed PCM, 256 samples/frame.
use crate::audio_intervals::AudioInterval;

const FRAME: usize = 256;
const CONTINUE_THRESHOLD: f32 = 0.5;
const LOOKBACK_FRAMES: u64 = 6;
const END_SILENCE_FRAMES: u64 = 10;

pub struct Vad {
    start_threshold: f32,
    detector: earshot::Detector,
    pending: [i16; FRAME],
    used: usize,
    position: u64,
    candidate: Option<u64>,
    active: bool,
    last_voice_end: u64,
    intervals: Vec<AudioInterval>,
}
#[cfg(test)]
impl Default for Vad {
    fn default() -> Self {
        Self::new(0.5).expect("valid default VAD threshold")
    }
}
impl Vad {
    pub fn new(start_threshold: f64) -> Result<Self, &'static str> {
        if !(0.3..=1.0).contains(&start_threshold) {
            return Err("VAD start threshold must be between 0.3 and 1.0");
        }
        Ok(Self {
            start_threshold: start_threshold as f32,
            detector: earshot::Detector::default(),
            pending: [0; FRAME],
            used: 0,
            position: 0,
            candidate: None,
            active: false,
            last_voice_end: 0,
            intervals: Vec::new(),
        })
    }
}
impl Vad {
    pub fn push(&mut self, mut samples: &[i16]) -> Result<(), &'static str> {
        while !samples.is_empty() {
            let n = (FRAME - self.used).min(samples.len());
            self.pending[self.used..self.used + n].copy_from_slice(&samples[..n]);
            self.used += n;
            samples = &samples[n..];
            if self.used == FRAME {
                let score = self.detector.predict_i16(&self.pending);
                self.accept(score, FRAME as u64)?;
                self.used = 0;
            }
        }
        Ok(())
    }
    fn accept(&mut self, score: f32, samples: u64) -> Result<(), &'static str> {
        let start = self.position;
        self.position = self
            .position
            .checked_add(samples)
            .ok_or("VAD position overflow")?;
        if !self.active {
            if score >= CONTINUE_THRESHOLD {
                let candidate = self.candidate.get_or_insert(start);
                *candidate =
                    (*candidate).max(start.saturating_sub((LOOKBACK_FRAMES - 1) * FRAME as u64));
            } else if score < self.start_threshold {
                self.candidate = None;
            }
            if score >= self.start_threshold {
                self.candidate.get_or_insert(start);
                self.last_voice_end = self.position;
                self.active = true;
            }
        } else if score >= CONTINUE_THRESHOLD {
            self.last_voice_end = self.position;
        } else if self.position - self.last_voice_end >= END_SILENCE_FRAMES * FRAME as u64 {
            self.close()?;
        }
        Ok(())
    }
    fn close(&mut self) -> Result<(), &'static str> {
        if self.active {
            self.intervals
                .try_reserve(1)
                .map_err(|_| "VAD interval allocation failed")?;
            self.intervals.push(AudioInterval {
                start_frame: self.candidate.unwrap(),
                end_frame: self.last_voice_end,
            });
        }
        self.candidate = None;
        self.active = false;
        Ok(())
    }
    pub fn finish(mut self) -> Result<Vec<AudioInterval>, &'static str> {
        if self.used > 0 {
            self.pending[self.used..].fill(0);
            let score = self.detector.predict_i16(&self.pending);
            self.accept(score, self.used as u64)?;
        }
        self.close()?;
        Ok(self.intervals)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn single_frame_starts_and_is_retained_at_each_boundary() {
        for threshold in [0.3, 0.5, 1.0] {
            let mut vad = Vad::new(threshold).unwrap();
            vad.accept(threshold as f32, FRAME as u64).unwrap();
            assert_eq!(
                vad.finish().unwrap(),
                vec![AudioInterval {
                    start_frame: 0,
                    end_frame: FRAME as u64
                }]
            );
        }
        assert!(Vad::new(0.299).is_err());
        assert!(Vad::new(1.001).is_err());
        assert!(Vad::new(f64::NAN).is_err());
    }

    #[test]
    fn low_start_threshold_and_fixed_continuation_threshold() {
        let mut vad = Vad::new(0.3).unwrap();
        vad.accept(0.29, FRAME as u64).unwrap();
        vad.accept(0.3, FRAME as u64).unwrap();
        for _ in 0..END_SILENCE_FRAMES {
            vad.accept(0.4, FRAME as u64).unwrap();
        }
        assert_eq!(
            vad.finish().unwrap(),
            vec![AudioInterval {
                start_frame: FRAME as u64,
                end_frame: 2 * FRAME as u64
            }]
        );
    }

    #[test]
    fn lookback_and_continuation() {
        let mut vad = Vad::new(0.8).unwrap();
        for _ in 0..20 {
            vad.accept(0.5, FRAME as u64).unwrap();
        }
        vad.accept(0.8, FRAME as u64).unwrap();
        vad.accept(0.5, FRAME as u64).unwrap();
        for _ in 0..END_SILENCE_FRAMES {
            vad.accept(0.49, FRAME as u64).unwrap();
        }
        assert_eq!(
            vad.finish().unwrap(),
            vec![AudioInterval {
                start_frame: 15 * FRAME as u64,
                end_frame: 22 * FRAME as u64
            }]
        );
    }

    #[test]
    fn partial_tail_and_silence() {
        let mut vad = Vad::default();
        vad.accept(0.5, 17).unwrap();
        assert_eq!(
            vad.finish().unwrap(),
            vec![AudioInterval {
                start_frame: 0,
                end_frame: 17
            }]
        );
        let mut vad = Vad::default();
        for _ in 0..100 {
            vad.push(&[0; 317]).unwrap();
        }
        assert!(vad.finish().unwrap().is_empty());
    }
}
