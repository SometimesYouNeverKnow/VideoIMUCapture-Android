# Findings

What the analysis scripts have settled, what they have not, and what that means for data
already shot. Dated, newest first. One phone: Galaxy S24 Ultra, Android 16. Every number here
is reproducible from the named session with the named script; the sessions themselves are not
in git (see [README.md](README.md)).

## 2026-09-21

The modularization pass (branch `modularize`) driven on the phone with `drive_cell.ps1`, phone
still, `lens_set` = all. Sessions pulled as `L1e`, `M2e`, `M3e`, `M5e`, `zoom3`.

### 1. The refactor behaves as the code it replaced

On `310c1e5`: M1, M3, M4, M5, M6, L1, W1 all `agrees: true`. Every stills cell ran the six-pair
sequence (the phone was left on the all-lens set), so the pair sequence, the single
restore-preview path, the focus stack's restore and periodic start/stop were each exercised,
and the log shows each restore once where it should be.

Receipts can agree while rows lie, so L1 was read back too. `dump_meta.py L1e`: twelve rows,
all `REQUEST'S FRAME`, zoom 0.60, `|dt|` 0.000 ms. `analyze_pairs.py L1e`: uw -> main 1.669
(74/418) against census 1.636, main -> 3x 2.645 (38/268) against 2.655; effective focals 777.1
and 3429.7 px against 792.5 and 3443.5. The same result as L1d on `09e5e47`, in daylight
(main at ISO 297 rather than 5887), from different classes.

### 2. M2 did not agree, and the refactor did not do it

M2 -- video only, no interval -- ran straight after an M1 that fired six pairs and sealed with
"6 armed, 12 rows", no stereo file on the card, `agrees: false`. The stereo counters were
reset where pairs are STARTED, so a session that starts none reset nothing; the reset sites
were the same three before the refactor. The same placement was wrong the other way: a stills
run joining a video reset the row count under the video's periodic pairs, and the periodic
count was only ever reset by the next periodic run. Fixed in `c445d7f` (reset where a session
OPENS, never where it is joined) and confirmed: W1 12/24, then M1 6/12 (not 18), then M2 0/0,
then M4 0/0, all agreeing.

### 3. The lost frame records: which counter, and where (issue #3)

`frame_holes.py`. The counter is `time_dropped_unmatched` in every case seen: an encoder frame
whose capture result never arrived as a row.

| clip | lost | where |
|---|---|---|
| M2e, video only | 1 | frame 0: the first row is frame 1 |
| M3e, stills then video, six-pair sequence running | 15 | frames 0-1, then 5-7, 40-42, 72-75, 142-144 |
| W1 08:55 | 1 | not pulled; same counter |
| M2 08:56, M4 x2, W1 08:51 | 0 | |

So the intermittent single loss IS the start edge: the clip's first encoded frame has no
partner, some of the time. And M3 shows a second, separate cause. Its pairs were kept at
-1.44, -0.24, +0.91, +2.05, +3.22 and +4.35 s of video time, which puts the request swaps
(about 0.35 s after each keep) at +0.12, +1.26, +2.40, +3.57 and the final preview restore at
+4.70. The holes are at +0.07, +1.34, +2.45 and +4.79 s, each 3-4 frames across a 134-168 ms
gap. When the repeating request is replaced under a recording, the encoder goes on producing
frames for which no result row is written. One swap lost nothing -- main+5x to 3x+5x, at
+3.57 s -- and why that one did not is not known.

That is also a hazard in its own right. `startRunStereo` declines a one-shot pair when a video
is ALREADY recording, because the warm-up replaces the recording's request. It cannot decline
for a video that joins two seconds later: in M3 the sequence was already running, and for about
five seconds the clip was fed by TEMPLATE_PREVIEW warm-up requests at zoom 0.6. Whether those
frames are wide-lens frames cannot be read from the file -- frame rows carry no zoom_ratio,
and their focal length and crop never change -- and the mp4 was not pulled. Unverified, and
worth a cell of its own.

### 4. The probe has never written its results, until now

Verifying the probe's move to `probe/` produced a 235-byte file: a device name and an
exception. So did last night's 19:32 run, to the byte. The streaming stage ends by killing the
camera service on purpose; the loop then asked for the next camera id's characteristics, got
"unknown device 1", and the exception discarded every stage's output because the result
arrays were attached after the loop. Last night's verdict survived only because the zoom
stage's frames are JPEGs. Fixed in `eee76bc`; the file is now 14-17 kB.

Running it twice in a row is not clean: the second run, three minutes after the first had
killed the service, lost `z0.6_uw` to CAMERA_ERROR 3 and six streaming cases to "could not
open device". A third run on a settled service was complete.

### 5. With no crop requested, the HAL admits the ultrawide crop

`zoom_probe.py zoom3`, "what the HAL claims", readable for the first time:

| case | zoom reported | ultrawide per-physical crop | main per-physical crop |
|---|---|---|---|
| z1.0 both | 1.0 | **576,432 - 3424,2569** | 0,0 - 4080,3060 |
| z0.6 both | 0.6 | 0,0 - 4000,3000 | 0,0 - 4080,3060 |
| z0.6 uw alone | 0.6 | 0,0 - 4000,3000 | |
| z1.0 uw alone | 1.0 | 576,432 - 3424,2569 | |

4000 / 2848 = **1.404** -- the factor the pixels gave for this same probe session last night
(z0.6 uw against z1.0 uw: 1.404, 871/1017). So the statement in 2026-09-20 §1 needs its
condition attached: the rows report the full array when the app has ASKED for the full array
per physical camera (`applyPhysicalFullArrays`), which the HAL echoes and does not honour.
Asked nothing, it reports what it did. That suggests a cheap experiment for the periodic path,
whose ultrawide half is still cropped: leave the per-physical crop unset and see whether the
row then carries the real window -- if it does, the effective focal is in the file and needs
no per-session measurement. Not run. And not yet a license to trust it: in the app's own
sessions the pixels said 1.62x, and what the HAL would have claimed there is unknown.

This morning's pixel fits are weaker than last night's (z0.6 uw -> main 1.510 on 56/501; the
two ultrawide-alone fits found nothing): the phone was 0.24 m from its subject, where an
18 mm baseline is a large parallax and a similarity is a poor model. They do not contradict
last night's; they do not add to it either.

Streaming stage, complete in a file for the first time: every pair streams from a session
bound with all four lenses, 2+5+6 streams, and 2+5+6+7 targeted at once is DEVICE DIED
(error 4). As observed on 2026-09-20, now recorded.

## 2026-09-20

Sessions: `L1` 19:02 and `M5` 19:05 (before any fix), zoom probe 19:31, `L1c` 19:53 on
`bfc822f`, `L1d` 20:09 on `09e5e47`, then W1/W2/N1/N2/M5 driven from the desk on `7e316fd`.

### 1. The ultrawide stream is cropped toward the main camera, and the HAL says it is not

`analyze_pairs.py`, similarity scale ultrawide -> main. The census intrinsics predict 1.636.

| session | zoom | measured | inliers | |
|---|---|---|---|---|
| L1 | 1.0 | 1.009 | 19/142 | same framing as the main camera |
| M5 | 1.0 | 1.008 | 20/137 | same |
| bare probe session | 1.0 | 1.181 | 439/713 | a *different* crop |

Every row in those sessions reports `crop_region` as the full 4000x3000 array. The crop is in
the HAL's stream path (Samsung's SAT), not in the request, and it **varies with the session**:
1.62x inside the app, 1.39x in a bare probe session.

So at zoom 1.0 the census ultrawide intrinsics do not describe the ultrawide stream. Its
effective focal at 1080p measured 1285 px against a census 792.5. The 18.02 mm baseline is
still real — it is where the lens sits — but `Z = f*B/d` with the census `f` underestimates
depth by that factor.

### 2. `CONTROL_ZOOM_RATIO` 0.6 lifts the crop

`zoom_probe.py` on `StereoProbe.probeZoom`'s frames:

| fit | measured | if uncropped | inliers |
|---|---|---|---|
| z1.0 uw -> z1.0 main (control) | 1.181 | 1.636 | 439/713 |
| **z0.6 uw -> z1.0 main** | **1.658** | 1.636 | 335/544 |
| z0.6 uw -> z1.0 uw | 1.404 | 1.636 | 871/1017 |
| z0.6 uw targeted alone -> z1.0 main | 1.660 | 1.636 | 406/613 |
| z1.0 uw targeted alone -> z1.0 main | 1.182 | 1.636 | 412/639 |
| z0.6 main -> z1.0 main | 1.000 | 1.000 | 2374/2511 |

At 0.6 the ultrawide stream is the wide lens; the main camera's stream does not change; and
the crop is a consequence of zoom 1.0, not of pairing the lenses. The pair warm-ups now put the
REPEATING request at 0.6 before a pair is kept (`applyFullFieldOfView`). A one-shot request at
0.6 dropped into a stream running at 1.0 reported 1.0 in five bursts of six.

The periodic path — pairs kept inside a video — deliberately does **not** do this: that request
drives the video, and 0.6 would switch every clip to the wide lens.

The first probe run failed with "disabled by policy". That was the phone locking mid-run and
revoking the camera, not the HAL. The probe now holds the screen on, runs its zoom stage before
the streaming stage, and reports a refused open instead of returning nothing.

### 3. Field 29 caught the rows describing a frame the file did not hold

`L1c` had the right pixels (uw -> main 1.646, 35/84; main -> 3x 2.670, 67/234) and wrong rows.
`dump_meta.py`: all twelve stereo rows were WARM-UP FRAMES, `time_ns` 213-634 ms before
`logical_result_time_ns`, with `zoom_ratio` 1.00 on pixels shot at 0.6, and three pairs of six
with halves one frame (41.8 ms) apart. The row described the still request's frame; the file
held a warm-up frame an armed reader had already kept.

Fixed in `09e5e47`: there is no still request. A pair is chosen from the warmed stream by
target timestamp — the periodic path's mechanism — and its rows come from the matching
repeating result. `L1d` confirms it: all twelve rows `REQUEST'S FRAME`, zoom 0.60, `|dt|`
0.000 ms on every pair, receipt agrees, on a build labelled with its own commit.

Also fixed on the way: `burst_size` said 4 on a pair burst (now 2), and one-shot rows did not
carry the image's own stamp, so simultaneity was an assumption rather than a reading.

### 4. At zoom 0.6 the ultrawide, main and 3x streams carry the census intrinsics

| | L1c | L1d | census |
|---|---|---|---|
| uw -> main | 1.646 (35/84) | 1.656 (88/139) | 1.636 |
| main -> 3x | 2.670 (67/234) | 2.630 (29/84) | 2.655 |
| uw effective f at 1920 | 788.0 px | 783.1 px | 792.5 px |
| 3x effective f at 1920 | 3462.6 px | 3410.2 px | 3443.5 px |

`overlay.py` on L1d, placing each lens inside the ultrawide frame: main x1.657 (94 inliers)
against census x1.636; 3x x4.335 against x4.345, chained through the main camera (36 inliers)
because SIFT does not bridge a 4.3x scale jump directly — uw -> 3x fits 10/33 where main -> 3x
fits 29/84.

### 5. No baseline for the 3x or 5x yet, and the 5x has no FOV confirmation

The scene, not the method. Triangulated depth from the uw+main pair was 0.34-0.41 m (L1, M5)
and 0.57-0.78 m (L1c, L1d). The 3x focuses no closer than 0.4 m and the 5x no closer than
0.8 m, so both telephotos sat at their near limit, out of focus, at ISO 2000-3200. No pair
with the 5x in it produced a usable fit in any session (under 12 inliers, or RANSAC collapsed
to a point), and at most 6 main<->tele matches carried a metric point — too few for PnP. G1/G2 at 1-2 m on a flat textured target is the scene this needs, and
`analyze_pairs.py`'s FOV table has to come back sane for all four lenses on that shot before
any baseline from it is believed.

### 6. N1 vs N2: the HAL's denoise removes most of what the ultrawide sensor produces

`wn_compare.py`, one pair per cell, same scene:

| cell | lens | noise sigma | detail | JPEG bytes |
|---|---|---|---|---|
| N1 as shipped | main | 1.48 | 23.41 | 494,343 |
| N1 as shipped | uw | 1.48 | 18.10 | 472,677 |
| N2 edge + NR off | main | 2.97 | 37.92 | 820,274 |
| N2 edge + NR off | uw | 8.90 | 104.03 | 1,391,101 |

Turning the HAL's processing off doubles residual noise on the main camera and multiplies it
by six on the ultrawide, with JPEGs 1.7x and 2.9x larger. Every frame shot before the setting
existed had that processing on. One dark indoor scene; the ratio will differ in daylight.

### 7. W1 vs W2: no detectable change on the main camera; the ultrawide is unanswered

Three matched long edges in the outer 30% of the main frame, RMS deviation from a straight
line, distortion correction off -> on: 0.39 -> 0.48, 0.45 -> 0.45, 0.70 -> 0.45 px. All
sub-pixel, differences within noise, consistent with a main lens whose census distortion is
small. On the ultrawide the check was inconclusive: dark noisy frames, and the edge matcher
latched three W1 edges onto one W2 segment. W's verdict wants a long straight line near the
frame edge, in light.

### 8. What driving cells from the desk found in the receipts

Fixed in `7e316fd`:

- The receipt disagreed with two good clips because the periodic path delivers the metric pair
  by design on the all-lens set. It now judges against `lenses_expected`, not
  `lenses_configured`.
- The stereo row counter never reset between video clips (24, 48, would have been 72).
- A crop warning fired on every clip from iterating four readers against a two-lens builder.

And one the receipt did not catch: the 18:53 L1 said `agrees: true` while logcat said
`Physical camera id: 5 is not valid!` five times. Read the receipt **and** the log;
`drive_cell.ps1` prints both.

## What this means for data already shot

- **One-shot pairs from `09e5e47` on are trustworthy end to end**: census intrinsics for
  uw/main/3x, rows describe the pixels, halves in one sensor period. `session.json` carries
  `git_sha`, so a session can say which side of the line it is on.
- **Every pair before that, and periodic pairs inside video still,** have an ultrawide half
  cropped by a session-dependent 1.4-1.6x, and (one-shot only) rows whose zoom and exposure
  describe a different frame. Their depths need the effective focal measured per session:
  `analyze_pairs.py`, uw -> main scale, `f_eff = f_main / scale`.
- **The 5x is unconfirmed** in every session to date.

## Open

- **G1/G2** (target + tape, 1 m then 2 m): first baseline numbers for the 3x and 5x, and the
  5x's FOV. **H1/H2, I1/I2, O1/O2** need a walk. None is drivable from a still phone.
- **Periodic pairs** still carry the zoom-1.0 crop with nothing in the row to say so.
- **Lost frame records** (issue #3): both causes fixed. The start-edge loss of frame 0 in
  `1102d89`: the last twelve results from before the press are handed to the writer, and
  `FramePairing` counts the ones older than encoder frame 0 as pre-roll rather than as losses.
  Eight clips on that build (M2 x3, W1 x2, M4 x2, M3; sessions `F0_1`..`F0_8`): every one
  starts at frame 0, no holes, `complete: true`. Against a prior rate of about eight clips in
  seventeen, eight clean by chance is under 1%. In seven the encoder's frame 0 was exposed
  after the press and all twelve remembered results were discarded; in one (M3) eleven were,
  because the twelfth WAS frame 0's -- the case the old code lost. Still unmeasured: rows at
  any other swap of the repeating request under a recording (torch, a blur-budget exposure
  hold).
- **W on the ultrawide** needs a proper scene (above).
- **Downstream**: the crop finding is written up on ReconStab #6 and the N1/N2 numbers on
  ReconStab #55 (2026-09-21). Still owed there: N1/N2 scored by the matcher on a real route,
  and a per-session effective focal for every archive pair.
- **Code structure**: the modularization pass is verified on the phone (2026-09-21 §1) and
  merged as v0.21. Not done, on purpose: `stereo/` and `session/` packages. Those classes call
  back into the capture core, so the move means widening a few dozen members to public across
  a circular boundary.
- **A pair sequence under a joining video** (2026-09-21 §3): CLOSED, verified on the phone.
  M3 on the all-lens set, three builds from one spot: 15 frame rows lost on `310c1e5`; 3 on
  v0.21 `3dad053`, where the joining video ends the sequence (`724a55f`) -- one hole, frames
  4-6 at +0.10 s, because the restore takes about four frames to reach the pixels; 0 on
  `9a6e938` and again on `edfc4d5`, where the recording's first frame and its rows wait
  400 ms after a warm-up had to be ended. The receipt reads "pair sequence ended at 2 of 6
  (video joined)", carries `stereo_sequence_cut`, and agrees; the stillness trigger resumes at
  once. On the metric pair (the single-pair path) M1 and M3 agree too, and a video that joins
  in the tail of a warm-up whose pair is already kept is not reported as a cut.
- **Periodic pairs with the per-physical crop left unset** (2026-09-21 §5): does the row then
  say 1.4x? One W-style cell would answer it.
