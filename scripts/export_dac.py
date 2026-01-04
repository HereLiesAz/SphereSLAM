import torch
import torch.onnx
# import depth_any_camera # Assuming the library is installed or available in path

def export_model():
    # Placeholder for model loading
    # model = depth_any_camera.load_model("vit_small")
    # model.eval()

    # Dummy input for Equirectangular resolution (e.g. 512x1024)
    dummy_input = torch.randn(1, 3, 512, 1024)

    # Export
    output_path = "dac_360.onnx"
    print(f"Exporting model to {output_path}...")

    # torch.onnx.export(
    #     model,
    #     dummy_input,
    #     output_path,
    #     opset_version=17,
    #     input_names=['input'],
    #     output_names=['depth'],
    #     dynamic_axes={'input': {0: 'batch_size'}, 'depth': {0: 'batch_size'}}
    # )

    print("Export complete (Conceptual).")

if __name__ == "__main__":
    export_model()
