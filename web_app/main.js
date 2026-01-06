// Assuming emcc output is named sphereslam_web.js
import createSphereSLAMModule from '../web_lib/build/sphereslam_web.js';

const statusDiv = document.getElementById('status');
const startBtn = document.getElementById('start-btn');
const captureBtn = document.getElementById('capture-btn');

let slamSystem = null;
let video = null;
let canvas = null;
let ctx = null;
let requestId = null;

async function init() {
    try {
        statusDiv.innerText = "Status: Loading Wasm...";

        // Initialize the module
        const Module = await createSphereSLAMModule();

        statusDiv.innerText = "Status: Wasm Loaded";

        // Create SLAM System
        // Note: Paths must exist in MEMFS.
        // In a real robust app, we'd fetch them and write them to FS first.
        Module.FS.writeFile('ORBvoc.txt', ''); // Dummy file creation
        Module.FS.writeFile('Settings.yaml', '');

        slamSystem = new Module.System("ORBvoc.txt", "Settings.yaml");

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

        } catch (err) {
            console.error("SLAM Process Error", err);
            statusDiv.innerText = "Status: Processing Error";
            stopCamera();
            return;
        }
    }

    requestId = requestAnimationFrame(processVideoFrame);
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

    } catch (err) {
        console.error(err);
        statusDiv.innerText = "Status: Camera Error";
    }
});

captureBtn.addEventListener('click', () => {
    if (!canvas) return;

    // Capture canvas as image
    const dataURL = canvas.toDataURL('image/jpeg');

    // Create download link
    const link = document.createElement('a');
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    link.download = `photosphere_capture_${timestamp}.jpg`;
    link.href = dataURL;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
});

// Auto-init on load
init();
console.log("Web App Script Loaded");
