# Kotlin Protracker Demo
### by Mark Abersold
https://www.linkedin.com/in/markabersold/

## Description

Loads and plays a ProTracker mod file, written in Kotlin and running on the JVM.

## How to run

If you have gradle installed on your system, simply use the default build and run tasks.

    gradle build
    gradle run

If you do not have gradle installed, you can instead use gradlew or gradlew.bat (if you are on a Windows system) included in the root directory.

    gradlew build
    gradlew run

## How it works

### Resampling algorithm

The heart of any mod player is in the resampling algorithm, as it is required to be able to play the instrument audio data at varying pitches.

The audio data for the instruments is a simple collection of signed bytes. All Protracker audio data is 8-bit. A note command in a Protracker mod has several pieces of information, including the period and the instrument number. The instrument number is simply used to determine the correct audio data to play. The period tells us at what pitch we should play it. From this information, we should be able to derive how to resample the audio data.

_**Semantic disclaimer:** Because the word "sample" has more than one meaning in this context, I chose to only use sample to refer to the individual numbers within a PCM audio stream or collection. "Sample" is typically also used to refer to the individual instruments within a module, but in this documentation and in my code, I either refer to them as "instruments" or "audio data."_

#### What is Interpolation?

Most, if not all ProTracker instruments store audio data at a much higher frequency than it is to actually be played. This means we will need to down-sample the audio data and interpolate values in between the original samples. For example, let's say we have the following two samples in our original PCM data:

    10 18

Let's also say we need to downsample to the point that there are three additional samples in between the original samples. A poorly implemented algorithm might do this with no interpolation, like this:

    10 10 10 10 18

But a better algorithm might do linear interpolation:

    10 12 14 16 18

#### My implementation

My algorithm aims to do linear interpolation. Here's how it works. First, we perform a calculation to determine the number of samples per second for a given period with the following formula:

    samplesPerSecond = 7093789.2 / (period * 2)

_(7093789.2 is the clock rate of a PAL Amiga computer - we could also implement this with the NTSC clock rate, which I could include as an option later)_

Next, we need to determine how many samples we will interpolate when given two samples. We can start by dividing the sampling rate by the result of the above formula:

    samplesToInterpolate = 44100 / samplesPerSecond

This usually does not result in an integer, which means the number of samples to interpolate will not always be the same between iterations. We do a little bit of additional math to figure this out:

    samplesToInterpolate = floor(44100 / samplesPerSecond)
    maximumCounterValueForExtraIteration = (44100 - (samplesPerSecond * iterationsPerSample))

As we progress through our audio data, we maintain a counter, that starts at 0.0 (yes, it's a double). If the counter is less than the maxCounterValueForExtraIteration, then we increase samplesToInterpolate by one. Otherwise we do not increase it.

The purpose of this counter is to maintain smooth interpolation. Every time we produce a new sample, we add the samplesPerSecond to that counter. If the counter is greater than our sampling rate, we subtract the sampling rate from the counter and then stop interpolating the current pair of samples, and then move on to the next pair. We do not reset the counter to 0.0 unless we start playing a new note.

I realize there may be better ways to resample but this is how I'm implementing it for the purposes of this demo, and so far it seems to work.