# CallX Server — Compress Endpoints Setup (v25)

## Kyun Chahiye?

v25 mein mobile pe video/audio processing hatai gayi hai.
Ab ye sab server pe hota hai taaki **mobile garam na ho**.

| Feature         | Pehle (Mobile)         | Ab (Server)                  |
|----------------|------------------------|------------------------------|
| Video compress  | LiTr + H264/HEVC/AV1  | FFmpeg + Cloudinary           |
| Audio mix       | MediaCodec + MediaMuxer| FFmpeg                        |
| Mobile CPU      | 80-100% during upload  | ~5% (sirf network send)       |
| Mobile heating  | Strong                 | Almost none                   |

---

## Endpoints Jo Server Pe Chahiye

### 1. `POST /compress/video`

Video compress karo aur Cloudinary pe upload karo.

**Request:** `multipart/form-data`

| Field            | Type   | Description                          |
|-----------------|--------|--------------------------------------|
| `file`           | file   | Raw MP4 video                        |
| `api_key`        | string | Cloudinary API key (sign se aata hai)|
| `timestamp`      | string | Sign timestamp                       |
| `signature`      | string | Cloudinary signature                 |
| `cloud_name`     | string | Cloudinary cloud name                |
| `quality_preset` | string | `360p` / `480p` / `720p` / `1080p` / `original` |
| `original_width` | int    | Original video width px              |
| `original_height`| int    | Original video height px             |
| `duration_ms`    | int    | Video duration milliseconds          |

**Response:** `application/json`

```json
{
  "video_url": "https://res.cloudinary.com/.../video.mp4",
  "thumb_url": "https://res.cloudinary.com/.../thumb.jpg",
  "public_id": "callx/videos/file/abc123",
  "compressed_bytes": 4200000
}
```

**Node.js Example (Express + FFmpeg + Cloudinary):**

```javascript
const express   = require('express');
const multer    = require('multer');
const { execFile } = require('child_process');
const cloudinary = require('cloudinary').v2;
const path      = require('path');
const fs        = require('fs');
const os        = require('os');

const upload = multer({ dest: os.tmpdir() });

app.post('/compress/video', upload.single('file'), async (req, res) => {
  const { quality_preset, original_width, original_height,
          api_key, timestamp, signature, cloud_name } = req.body;

  const inputPath  = req.file.path;
  const outputPath = inputPath + '_compressed.mp4';

  // Quality preset → FFmpeg params
  const qualityMap = {
    '360p':    ['-vf', 'scale=-2:360',  '-b:v', '500k'],
    '480p':    ['-vf', 'scale=-2:480',  '-b:v', '1000k'],
    '720p':    ['-vf', 'scale=-2:720',  '-b:v', '2000k'],
    '1080p':   ['-vf', 'scale=-2:1080', '-b:v', '4000k'],
    'original': [],
  };
  const ffmpegArgs = qualityMap[quality_preset] || qualityMap['480p'];

  try {
    // Step 1: FFmpeg compress
    await new Promise((resolve, reject) => {
      execFile('ffmpeg', [
        '-i', inputPath,
        ...ffmpegArgs,
        '-c:v', 'libx264',
        '-c:a', 'aac',
        '-preset', 'fast',
        '-movflags', '+faststart',
        '-y', outputPath
      ], (err) => err ? reject(err) : resolve());
    });

    // Step 2: Cloudinary upload
    cloudinary.config({ cloud_name, api_key, api_secret: process.env.CLOUDINARY_SECRET });

    const [videoResult, thumbResult] = await Promise.all([
      cloudinary.uploader.upload(outputPath, {
        resource_type: 'video',
        folder: 'callx/videos/file',
        timestamp, signature, api_key,
      }),
      cloudinary.uploader.upload(inputPath, {
        resource_type: 'video',
        folder: 'callx/videos/thumb',
        eager: [{ width: 300, height: 300, crop: 'fill', format: 'jpg' }],
      })
    ]);

    const compressedBytes = fs.statSync(outputPath).size;

    res.json({
      video_url:        videoResult.secure_url,
      thumb_url:        thumbResult.eager?.[0]?.secure_url || '',
      public_id:        videoResult.public_id,
      compressed_bytes: compressedBytes,
    });

  } finally {
    // Cleanup temp files
    fs.unlink(inputPath, () => {});
    fs.unlink(outputPath, () => {});
  }
});
```

---

### 2. `POST /audio/mix`

Video aur music mix karo FFmpeg se.

**Request:** `multipart/form-data`

| Field           | Type   | Description                      |
|----------------|--------|----------------------------------|
| `video`         | file   | Video file with mic audio        |
| `music_url`     | string | Background music URL (optional)  |
| `voiceover`     | file   | Voiceover AAC file (optional)    |
| `mic_vol`       | float  | Mic volume 0.0–1.0               |
| `music_vol`     | float  | Music volume 0.0–1.0             |
| `voiceover_vol` | float  | Voiceover volume (optional)      |

**Response:** `application/json`

```json
{
  "output_url": "https://res.cloudinary.com/.../mixed.mp4",
  "public_id":  "callx/audio/mixed/xyz"
}
```

**Node.js Example:**

```javascript
app.post('/audio/mix', upload.fields([
  { name: 'video', maxCount: 1 },
  { name: 'voiceover', maxCount: 1 }
]), async (req, res) => {
  const videoFile   = req.files['video']?.[0];
  const voiceover   = req.files['voiceover']?.[0];
  const { music_url, mic_vol = '1.0', music_vol = '0.5', voiceover_vol = '1.0' } = req.body;

  const outputPath = path.join(os.tmpdir(), `mixed_${Date.now()}.mp4`);

  // Build FFmpeg filter_complex for mixing
  let inputs   = ['-i', videoFile.path];
  let filters  = [];
  let audioMap = `[0:a]volume=${mic_vol}[mic]`;

  if (music_url) {
    inputs.push('-i', music_url);
    filters.push(audioMap);
    filters.push(`[1:a]volume=${music_vol}[music]`);
    filters.push('[mic][music]amix=inputs=2:duration=first[mixed]');
    audioMap = '[mixed]';
  } else {
    filters.push(`[0:a]volume=${mic_vol}[mixed]`);
    audioMap = '[mixed]';
  }

  const filterStr = filters.join(';');

  await new Promise((resolve, reject) => {
    execFile('ffmpeg', [
      ...inputs,
      '-filter_complex', filterStr,
      '-map', '0:v',
      '-map', audioMap,
      '-c:v', 'copy',
      '-c:a', 'aac',
      '-shortest',
      '-y', outputPath
    ], (err) => err ? reject(err) : resolve());
  });

  // Upload to Cloudinary
  const result = await cloudinary.uploader.upload(outputPath, {
    resource_type: 'video',
    folder: 'callx/audio/mixed',
  });

  fs.unlink(outputPath, () => {});
  fs.unlink(videoFile.path, () => {});

  res.json({
    output_url: result.secure_url,
    public_id:  result.public_id,
  });
});
```

---

## Server Requirements

```bash
# FFmpeg install karo (Render.com pe built-in hai)
apt-get install ffmpeg  # Ubuntu/Debian
# ya
brew install ffmpeg     # macOS dev

# Node packages
npm install express multer cloudinary
```

### Render.com pe FFmpeg

Render.com pe FFmpeg pehle se installed hota hai — kuch extra karna nahi.
Sirf ye endpoints apne existing `callx-server` mein add karo.

### Environment Variables

```
CLOUDINARY_SECRET=your_cloudinary_api_secret
```

---

## Testing

```bash
# Video compress test
curl -X POST https://callx-server.onrender.com/compress/video \
  -F "file=@test.mp4" \
  -F "quality_preset=480p" \
  -F "api_key=YOUR_KEY" \
  -F "timestamp=1234567890" \
  -F "signature=YOUR_SIG" \
  -F "cloud_name=dvqqgqdls"

# Expected response:
# { "video_url": "https://...", "thumb_url": "...", "compressed_bytes": ... }
```
