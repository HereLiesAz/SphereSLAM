// Assuming emcc output is named sphereslam_web.js
import createSphereSLAMModule from '../web_lib/build/sphereslam_web.js';

const statusDiv = document.getElementById('status');
const startBtn = document.getElementById('start-btn');
const captureBtn = document.getElementById('capture-btn');

let slamSystem = null;
let slamModule = null;
let video = null;
let canvas = null;
let ctx = null;
let requestId = null;

async function init() {
    try {
        statusDiv.innerText = "Status: Loading Wasm...";

        // Initialize the module
        const Module = await createSphereSLAMModule();
        window.Module = Module; // Expose for FS access

        statusDiv.innerText = "Status: Wasm Loaded";

        // Create SLAM System
        // Note: Paths must exist in MEMFS.
        // In a real robust app, we'd fetch them and write them to FS first.
        slamModule.FS.writeFile('ORBvoc.txt', ''); // Dummy file creation
        slamModule.FS.writeFile('Settings.yaml', '');

        slamSystem = new slamModule.System("ORBvoc.txt", "Settings.yaml");

        startBtn.disabled = false;
        statusDiv.innerText = "Status: Ready";

    } catch (e) {
        console.error("Failed to load Wasm", e);
        statusDiv.innerText = "Status: Error Loading Wasm";
    }
}

function stopCamera() {
    if (requestId) cancelAnimationFrame(requestId);
    if (video && video.srcObject) {
        video.srcObject.getTracks().forEach(track => track.stop());
    }
}

function processVideoFrame() {
    if (!slamSystem || !video || !ctx) return;

    if (video.readyState === video.HAVE_ENOUGH_DATA) {
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);

        // Robustness: Handle exceptions in C++ calls
        try {
            // Pass data to SLAM
            // We need to use the binding we defined.
            // imageData.data is a Uint8ClampedArray.

            slamSystem.processFrame(imageData.data, canvas.width, canvas.height, performance.now());

            // Update stats
            const stats = slamSystem.getMapStats();
            const state = slamSystem.getTrackingState();
            statusDiv.innerText = `Status: Tracking (${state}) - ${stats}`;

            // Visualize Points
            renderPoints();

        } catch (err) {
            console.error("SLAM Process Error", err);
            statusDiv.innerText = "Status: Processing Error";
            stopCamera();
            return;
        }
    }

    requestId = requestAnimationFrame(processVideoFrame);
}

function renderPoints() {
    const overlayCanvas = document.getElementById('ar-overlay');
    const overlayCtx = overlayCanvas.getContext('2d');

    // Sync sizes
    if (overlayCanvas.width !== canvas.width) {
        overlayCanvas.width = canvas.width;
        overlayCanvas.height = canvas.height;
    }

    overlayCtx.clearRect(0, 0, overlayCanvas.width, overlayCanvas.height);

    const points = slamSystem.getAllMapPoints(); // Float32Array [x,y,z, x,y,z...]
    const pose = slamSystem.getPose(); // Float32Array 16 elements (4x4)

    if (!points || !pose) return;

    // Simple Pinhole Projection
    // K (Intrinsics) approximation based on canvas size and ~90 deg FOV
    const f = canvas.width / 2.0;
    const cx = canvas.width / 2.0;
    const cy = canvas.height / 2.0;

    // Pose is Tcw (World to Camera).
    // OpenCV: X Right, Y Down, Z Forward.

    // Extract Rotation and Translation
    // Row-Major:
    // 0  1  2  3
    // 4  5  6  7
    // 8  9  10 11
    // 12 13 14 15

    const r00 = pose[0], r01 = pose[1], r02 = pose[2], tx = pose[3];
    const r10 = pose[4], r11 = pose[5], r12 = pose[6], ty = pose[7];
    const r20 = pose[8], r21 = pose[9], r22 = pose[10], tz = pose[11];

    overlayCtx.fillStyle = 'red';

    for (let i = 0; i < points.length; i += 3) {
        const Xw = points[i];
        const Yw = points[i+1];
        const Zw = points[i+2];

        // Transform to Camera Frame
        const Xc = r00*Xw + r01*Yw + r02*Zw + tx;
        const Yc = r10*Xw + r11*Yw + r12*Zw + ty;
        const Zc = r20*Xw + r21*Yw + r22*Zw + tz;

        if (Zc <= 0.1) continue; // Behind camera or too close

        // Project
        const u = f * (Xc / Zc) + cx;
        const v = f * (Yc / Zc) + cy;

        // Draw
        if (u >= 0 && u < canvas.width && v >= 0 && v < canvas.height) {
            overlayCtx.fillRect(u, v, 2, 2);
        }
    }
}

startBtn.addEventListener('click', async () => {
    if (!slamSystem) {
        await init(); // Try init if not ready
        if (!slamSystem) return;
    }

    statusDiv.innerText = "Status: Starting Camera...";

    try {
        const stream = await navigator.mediaDevices.getUserMedia({
            video: { width: 640, height: 480, facingMode: "environment" }
        });

        video = document.createElement('video');
        video.srcObject = stream;
        video.play();

        canvas = document.getElementById('camera-feed');
        ctx = canvas.getContext('2d');

        video.onloadedmetadata = () => {
            canvas.width = video.videoWidth;
            canvas.height = video.videoHeight;
            processVideoFrame();
        };

        startBtn.innerText = "Stop AR";
        startBtn.onclick = () => {
            stopCamera();
            startBtn.innerText = "Start AR";
            startBtn.onclick = null; // Reload page to restart effectively for simple demo
            location.reload();
        };

        captureBtn.disabled = false;
        document.getElementById('save-map-btn').disabled = false;
        document.getElementById('load-map-btn').disabled = false;

    } catch (err) {
        console.error(err);
        statusDiv.innerText = "Status: Camera Error";
    }
});

captureBtn.addEventListener('click', () => {
    if (!slamSystem) return;

    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const filename = `photosphere_${timestamp}.jpg`;

    slamSystem.savePhotosphere(filename);
    downloadFromMemFS(filename);
});

// Helper to download file from MEMFS
function downloadFromMemFS(filename) {
    if (!slamModule) return;
    try {
        const content = slamModule.FS.readFile(filename);
        const blob = new Blob([content], {type: 'application/octet-stream'});
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = filename;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
    } catch(e) {
        console.error("Failed to download " + filename, e);
        alert("Failed to download " + filename);
    }
}

document.getElementById('save-map-btn').addEventListener('click', () => {
    if (!slamSystem) return;
    const filename = "map.bin";
    slamSystem.saveMap(filename);
    downloadFromMemFS(filename);
});

document.getElementById('load-map-input').addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file || !slamSystem) return;

    const arrayBuffer = await file.arrayBuffer();
    const data = new Uint8Array(arrayBuffer);

    slamModule.FS.writeFile("map.bin", data);
    const success = slamSystem.loadMap("map.bin");

    if (success) {
        alert("Map Loaded Successfully!");
    } else {
        alert("Failed to Load Map.");
    }
});

document.getElementById('load-map-btn').addEventListener('click', () => {
    document.getElementById('load-map-input').click();
});

// Auto-init on load
init();
console.log("Web App Script Loaded");
