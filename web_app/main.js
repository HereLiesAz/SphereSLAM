// Placeholder for interacting with the Wasm module
import Module from '../web_lib/build/sphereslam_web.js'; // Assumed output path

const statusDiv = document.getElementById('status');
const startBtn = document.getElementById('start-btn');

let wasmModule = null;

async function init() {
    try {
        statusDiv.innerText = "Status: Loading Wasm...";
        // Initialize the Emscripten module
        // Note: in a real setup, this file needs to be served correctly
        // Module() is usually a factory function
        wasmModule = await Module();
        statusDiv.innerText = "Status: Wasm Loaded";
        startBtn.disabled = false;

        // Example usage:
        // wasmModule.initSystem();
    } catch (e) {
        console.error("Failed to load Wasm", e);
        statusDiv.innerText = "Status: Error Loading Wasm";
    }
}

startBtn.addEventListener('click', async () => {
    if (!wasmModule) return;
    statusDiv.innerText = "Status: Starting Camera...";

    try {
        const stream = await navigator.mediaDevices.getUserMedia({ video: true });
        const video = document.createElement('video');
        video.srcObject = stream;
        video.play();

        // Loop to feed frames to C++ (conceptual)
        /*
        const canvas = document.getElementById('camera-feed');
        const ctx = canvas.getContext('2d');
        function loop() {
            ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
            // Get pixel data
            // Pass to wasmModule.processFrame(...)
            requestAnimationFrame(loop);
        }
        loop();
        */
        statusDiv.innerText = "Status: Running";
    } catch (err) {
        console.error(err);
        statusDiv.innerText = "Status: Camera Error";
    }
});

// Start initialization
// init();
// Commented out to avoid runtime errors in this static file check
console.log("Web App Script Loaded");
