import UIKit
import AVFoundation

// Note: To use the Objective-C++ bridge, you must add "ios_lib/SphereSLAMBridge.h" to the bridging header.
// Assuming the module name is 'SphereSLAM' or linked directly.

class ViewController: UIViewController, AVCaptureVideoDataOutputSampleBufferDelegate {

    var slamBridge: SphereSLAMBridge?
    var captureSession: AVCaptureSession?
    var videoPreviewLayer: AVCaptureVideoPreviewLayer?

    // UI Elements
    let statusLabel: UILabel = {
        let label = UILabel()
        label.backgroundColor = UIColor.black.withAlphaComponent(0.5)
        label.textColor = .white
        label.text = "Initializing..."
        label.translatesAutoresizingMaskIntoConstraints = false
        return label
    }()

    let captureButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle("Capture Photosphere", for: .normal)
        button.backgroundColor = .white
        button.layer.cornerRadius = 10
        button.translatesAutoresizingMaskIntoConstraints = false
        return button
    }()

    let saveMapButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle("Save Map", for: .normal)
        button.backgroundColor = .systemBlue
        button.setTitleColor(.white, for: .normal)
        button.layer.cornerRadius = 10
        button.translatesAutoresizingMaskIntoConstraints = false
        return button
    }()

    let loadMapButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle("Load Map", for: .normal)
        button.backgroundColor = .systemGreen
        button.setTitleColor(.white, for: .normal)
        button.layer.cornerRadius = 10
        button.translatesAutoresizingMaskIntoConstraints = false
        return button
    }()

    override func viewDidLoad() {
        super.viewDidLoad()

        setupUI()

        // Initialize SLAM System
        initSLAM()

        // Check Permissions
        checkCameraPermissions()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        videoPreviewLayer?.frame = view.bounds
    }

    func setupUI() {
        view.backgroundColor = .black
        view.addSubview(statusLabel)

        view.addSubview(captureButton)
        captureButton.addTarget(self, action: #selector(capturePhotosphere), for: .touchUpInside)

        view.addSubview(saveMapButton)
        saveMapButton.addTarget(self, action: #selector(saveMap), for: .touchUpInside)

        view.addSubview(loadMapButton)
        loadMapButton.addTarget(self, action: #selector(loadMap), for: .touchUpInside)

        NSLayoutConstraint.activate([
            statusLabel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -20),
            statusLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),

            captureButton.bottomAnchor.constraint(equalTo: statusLabel.topAnchor, constant: -20),
            captureButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            captureButton.widthAnchor.constraint(equalToConstant: 200),
            captureButton.heightAnchor.constraint(equalToConstant: 50),

            saveMapButton.bottomAnchor.constraint(equalTo: captureButton.topAnchor, constant: -10),
            saveMapButton.trailingAnchor.constraint(equalTo: view.centerXAnchor, constant: -10),
            saveMapButton.widthAnchor.constraint(equalToConstant: 120),
            saveMapButton.heightAnchor.constraint(equalToConstant: 40),

            loadMapButton.bottomAnchor.constraint(equalTo: captureButton.topAnchor, constant: -10),
            loadMapButton.leadingAnchor.constraint(equalTo: view.centerXAnchor, constant: 10),
            loadMapButton.widthAnchor.constraint(equalToConstant: 120),
            loadMapButton.heightAnchor.constraint(equalToConstant: 40)
        ])
    }

    func initSLAM() {
        // In a real app, these paths would point to Bundle resources
        // We handle the case where they might be missing gracefully
        let vocPath = Bundle.main.path(forResource: "ORBvoc", ofType: "txt") ?? ""
        let settingsPath = Bundle.main.path(forResource: "Settings", ofType: "yaml") ?? ""

        if vocPath.isEmpty {
            print("Warning: Vocabulary file not found in Bundle.")
        }

        slamBridge = SphereSLAMBridge(vocFile: vocPath, settingsFile: settingsPath)
        updateStatus(text: "SLAM Initialized")
    }

    func checkCameraPermissions() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            setupCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                if granted {
                    DispatchQueue.main.async { self?.setupCamera() }
                }
            }
        case .denied, .restricted:
            updateStatus(text: "Camera Access Denied")
        @unknown default:
            break
        }
    }

    func setupCamera() {
        let session = AVCaptureSession()
        session.sessionPreset = .vga640x480 // Use a reasonable resolution for SLAM

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device) else {
            updateStatus(text: "Failed to access camera")
            return
        }

        if session.canAddInput(input) {
            session.addInput(input)
        }

        let output = AVCaptureVideoDataOutput()
        output.setSampleBufferDelegate(self, queue: DispatchQueue(label: "videoQueue"))
        // We want BGRA or YpCbCr. OpenCV often likes BGR or Gray.
        // kCVPixelFormatType_32BGRA is standard for iOS processing.
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)]

        if session.canAddOutput(output) {
            session.addOutput(output)
        }

        videoPreviewLayer = AVCaptureVideoPreviewLayer(session: session)
        videoPreviewLayer?.videoGravity = .resizeAspectFill
        if let layer = videoPreviewLayer {
            view.layer.insertSublayer(layer, at: 0)
        }

        captureSession = session
        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }

        updateStatus(text: "Camera Running")
    }

    func updateStatus(text: String) {
        DispatchQueue.main.async {
            self.statusLabel.text = text
        }
    }

    @objc func capturePhotosphere() {
        // Robust Implementation: Use SLAM Bridge to save actual photosphere
        let timestamp = Int(Date().timeIntervalSince1970)
        let filename = "photosphere_\(timestamp).jpg"
        let fileURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(filename)

        // Native Save
        slamBridge?.savePhotosphere(fileURL.path)

        // Wait briefly then check if file exists (since call is async in native usually but here sync)
        // If file exists, move to Photos
        DispatchQueue.global(qos: .background).async {
            // Check for both .jpg and .png as native might append ext
            let pngURL = fileURL.appendingPathExtension("png") // Wait, path already has .jpg. native might append .png -> .jpg.png if not careful.
            // System logic: if no ext, appends. If .jpg present, keeps it.

            if FileManager.default.fileExists(atPath: fileURL.path) {
                if let image = UIImage(contentsOfFile: fileURL.path) {
                    UIImageWriteToSavedPhotosAlbum(image, self, #selector(self.image(_:didFinishSavingWithError:contextInfo:)), nil)
                }
            } else {
                DispatchQueue.main.async {
                    self.updateStatus(text: "Failed to generate photosphere")
                }
            }
        }
    }

    @objc func saveMap() {
        let timestamp = Int(Date().timeIntervalSince1970)
        let filename = "map_\(timestamp).bin"
        let fileURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(filename)

        slamBridge?.saveMap(fileURL.path)
        updateStatus(text: "Map saved: \(filename)")
    }

    @objc func loadMap() {
        // Pick the latest map for simplicity
        let docDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        do {
            let files = try FileManager.default.contentsOfDirectory(at: docDir, includingPropertiesForKeys: nil)
            let maps = files.filter { $0.lastPathComponent.hasPrefix("map_") && $0.pathExtension == "bin" }
                            .sorted { $0.lastPathComponent > $1.lastPathComponent }

            if let latestMap = maps.first {
                let success = slamBridge?.loadMap(latestMap.path) ?? false
                updateStatus(text: success ? "Loaded \(latestMap.lastPathComponent)" : "Failed to load map")
            } else {
                updateStatus(text: "No maps found")
            }
        } catch {
            updateStatus(text: "Error finding maps")
        }
    }

    @objc func image(_ image: UIImage, didFinishSavingWithError error: Error?, contextInfo: UnsafeRawPointer) {
        DispatchQueue.main.async {
            if let error = error {
                self.updateStatus(text: "Capture Failed: \(error.localizedDescription)")
            } else {
                self.updateStatus(text: "Photosphere Saved to Photos!")
            }
        }
    }

    // MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        if let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) {
            let width = CVPixelBufferGetWidth(pixelBuffer)
            let height = CVPixelBufferGetHeight(pixelBuffer)
            let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
            let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds

            // Pass raw pointer to C++ Bridge
            slamBridge?.processFrame(baseAddress, width: Int32(width), height: Int32(height), stride: Int32(bytesPerRow), timestamp: timestamp)

            // Update UI with SLAM stats occasionally
            // Note: Don't flood main thread
             if Int(timestamp * 10) % 10 == 0 { // ~Every second
                 let state = slamBridge?.getTrackingState() ?? -1
                 let stats = slamBridge?.getMapStats() ?? ""

                 var stateStr = "UNKNOWN"
                 switch state {
                 case -1: stateStr = "NOT READY"
                 case 0: stateStr = "NO IMAGES"
                 case 1: stateStr = "NOT INIT"
                 case 2: stateStr = "TRACKING"
                 case 3: stateStr = "LOST"
                 default: stateStr = "\(state)"
                 }

                 updateStatus(text: "State: \(stateStr) | \(stats)")
             }
        }
    }
}
