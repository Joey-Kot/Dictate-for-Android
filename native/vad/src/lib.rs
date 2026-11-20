//! Earshot and the source-frame interval rules used by Dictate for Windows.
//! Only source-frame positions cross JNI. The caller owns the original audio.
mod audio_intervals;
mod vad;

use jni::{
    JNIEnv,
    objects::{JLongArray, JObject, JString},
    sys::{jdouble, jint, jlong, jlongArray},
};
use std::{error::Error, fs::File, io::Read, panic::AssertUnwindSafe, ptr};

fn analyze<'local>(
    env: &mut JNIEnv<'local>,
    path: JString<'local>,
    source_rate: jint,
    source_frames: jlong,
    padding_ms: jint,
    threshold: jdouble,
    cancellation: JObject<'local>,
) -> Result<Option<JLongArray<'local>>, Box<dyn Error>> {
    let path: String = env.get_string(&path)?.into();
    let mut input = File::open(path)?;
    let mut remaining = input.metadata()?.len();
    if remaining == 0 || remaining % 2 != 0 {
        return Err("Invalid VAD analysis PCM length".into());
    }
    let mut detector = vad::Vad::new(threshold)?;
    let mut bytes = [0_u8; 8192];
    let mut samples = [0_i16; 4096];
    while remaining > 0 {
        if env
            .call_method(&cancellation, "getAsBoolean", "()Z", &[])?
            .z()?
        {
            return Ok(None);
        }
        let count = remaining.min(bytes.len() as u64) as usize;
        input.read_exact(&mut bytes[..count])?;
        for (sample, pair) in samples.iter_mut().zip(bytes[..count].as_chunks::<2>().0) {
            *sample = i16::from_le_bytes(*pair);
        }
        detector.push(&samples[..count / 2])?;
        remaining -= count as u64;
    }
    let intervals = audio_intervals::prepare_intervals(
        detector.finish()?,
        source_rate.try_into()?,
        source_frames.try_into()?,
        padding_ms.try_into()?,
    )?;
    let length = intervals
        .len()
        .checked_mul(2)
        .ok_or("Too many VAD intervals")?;
    let array = env.new_long_array(length.try_into()?)?;
    // Bound the temporary JNI copy even for very long recordings.
    for (index, chunk) in intervals.chunks(256).enumerate() {
        let mut pairs = [0_i64; 512];
        for (out, interval) in pairs.as_chunks_mut::<2>().0.iter_mut().zip(chunk) {
            out[0] = interval.start_frame.try_into()?;
            out[1] = interval.end_frame.try_into()?;
        }
        env.set_long_array_region(&array, (index * 512).try_into()?, &pairs[..chunk.len() * 2])?;
    }
    Ok(Some(array))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_joeykot_dictate_audio_NativeVad_analyze<'local>(
    mut env: JNIEnv<'local>,
    _receiver: JObject<'local>,
    path: JString<'local>,
    source_rate: jint,
    source_frames: jlong,
    padding_ms: jint,
    threshold: jdouble,
    cancellation: JObject<'local>,
) -> jlongArray {
    let result = std::panic::catch_unwind(AssertUnwindSafe(|| {
        analyze(
            &mut env,
            path,
            source_rate,
            source_frames,
            padding_ms,
            threshold,
            cancellation,
        )
    }));
    let message = match result {
        Ok(Ok(Some(array))) => return array.into_raw(),
        Ok(Ok(None)) => return ptr::null_mut(), // cancelled
        Ok(Err(error)) => error.to_string(),
        Err(_) => "VAD analysis panicked".into(),
    };
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/io/IOException", message);
    }
    ptr::null_mut()
}
