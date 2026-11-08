// Standalone shader and pipeline precompiler for the Linux Remix runtime.
//
// Starts the runtime without a window, renders a small scene until every shader and pipeline has compiled,
// and shuts down cleanly so the runtime writes its pipeline cache (remix-pipeline.cache beside libremixapi.so).
// Run it from the game directory, so the same rtx.conf the game uses selects the shader variants to compile.
//
// Usage: remix_precompile <path to libremixapi.so> [timeout seconds, default 600]
// Exit code: 0 compiled, 1 timed out, other values mean the runtime failed to start.
#include <dlfcn.h>

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#define REMIX_ALLOW_X86
#include "remix_c.h"

int main(int argc, char** argv) {
  const char* lib = argc > 1 ? argv[1] : "./libremixapi.so";
  const double timeoutSeconds = argc > 2 ? atof(argv[2]) : 600.0;

  void* handle = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
  if (handle == nullptr) {
    fprintf(stderr, "precompile: cannot load %s: %s\n", lib, dlerror());
    return 10;
  }

  auto init = reinterpret_cast<PFN_remixapi_InitializeLibrary>(dlsym(handle, "remixapi_InitializeLibrary"));
  auto shaderCount = reinterpret_cast<uint32_t (*)()>(dlsym(handle, "remixinternal_GetShaderCompilationCount"));
  if (init == nullptr || shaderCount == nullptr) {
    fprintf(stderr, "precompile: %s is not a Remix runtime\n", lib);
    return 11;
  }

  remixapi_InitializeLibraryInfo info = {};
  info.sType = REMIXAPI_STRUCT_TYPE_INITIALIZE_LIBRARY_INFO;
  info.version = REMIXAPI_VERSION_MAKE(REMIXAPI_VERSION_MAJOR, REMIXAPI_VERSION_MINOR, REMIXAPI_VERSION_PATCH);
  remixapi_Interface api = {};
  if (init(&info, &api) != REMIXAPI_ERROR_CODE_SUCCESS) {
    fprintf(stderr, "precompile: runtime initialization failed\n");
    return 12;
  }

  remixapi_StartupInfo start = {};
  start.sType = REMIXAPI_STRUCT_TYPE_STARTUP_INFO;
  start.hwnd = nullptr;
  start.interopImage = 1;
  start.forceNoVkSwapchain = 1;
  if (api.Startup(&start) != REMIXAPI_ERROR_CODE_SUCCESS) {
    fprintf(stderr, "precompile: runtime startup failed\n");
    return 13;
  }

  // A small lit textured scene, so the material and surface shader variants compile too.
  const int size = 64;
  static uint8_t checker[size * size * 4];
  for (int y = 0; y < size; ++y) {
    for (int x = 0; x < size; ++x) {
      const bool on = ((x / 8) + (y / 8)) & 1;
      uint8_t* p = checker + (y * size + x) * 4;
      p[0] = on ? 230 : 40;
      p[1] = on ? 200 : 90;
      p[2] = on ? 60 : 160;
      p[3] = 255;
    }
  }

  remixapi_TextureFromMemoryInfo textureInfo = {};
  textureInfo.sType = REMIXAPI_STRUCT_TYPE_TEXTURE_FROM_MEMORY_INFO;
  textureInfo.hash = 0x1234;
  textureInfo.pixelData = checker;
  textureInfo.width = size;
  textureInfo.height = size;
  textureInfo.format = REMIXAPI_TEXTURE_FORMAT_RGBA8_SRGB;
  textureInfo.purpose = REMIXAPI_TEXTURE_PURPOSE_COLOR;
  remixapi_TextureHandle texture = nullptr;
  api.CreateTextureFromMemory(&textureInfo, &texture);

  remixapi_MaterialInfoOpaqueEXT opaque = {};
  opaque.sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_EXT;
  opaque.albedoConstant = { 1, 1, 1 };
  opaque.opacityConstant = 1.0f;
  opaque.roughnessConstant = 0.6f;
  opaque.alphaTestType = 7;
  remixapi_MaterialInfo materialInfo = {};
  materialInfo.sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO;
  materialInfo.pNext = &opaque;
  materialInfo.hash = 0x42;
  materialInfo.albedoTexture = texture;
  materialInfo.filterMode = 1;
  materialInfo.wrapModeU = 1;
  materialInfo.wrapModeV = 1;
  remixapi_MaterialHandle material = nullptr;
  api.CreateMaterial(&materialInfo, &material);

  remixapi_HardcodedVertex vertices[4] = {};
  const float positions[4][3] = { { -10, 0, -10 }, { 10, 0, -10 }, { 10, 0, 10 }, { -10, 0, 10 } };
  const float uvs[4][2] = { { 0, 0 }, { 4, 0 }, { 4, 4 }, { 0, 4 } };
  for (int i = 0; i < 4; ++i) {
    memcpy(vertices[i].position, positions[i], sizeof(float) * 3);
    vertices[i].normal[1] = 1;
    memcpy(vertices[i].texcoord, uvs[i], sizeof(float) * 2);
    vertices[i].color = 0xffffffff;
  }
  uint32_t indices[6] = { 0, 2, 1, 0, 3, 2 };
  remixapi_MeshInfoSurfaceTriangles surface = {};
  surface.vertices_values = vertices;
  surface.vertices_count = 4;
  surface.indices_values = indices;
  surface.indices_count = 6;
  surface.material = material;
  remixapi_MeshInfo meshInfo = {};
  meshInfo.sType = REMIXAPI_STRUCT_TYPE_MESH_INFO;
  meshInfo.hash = 1;
  meshInfo.surfaces_values = &surface;
  meshInfo.surfaces_count = 1;
  remixapi_MeshHandle mesh = nullptr;
  api.CreateMesh(&meshInfo, &mesh);

  remixapi_LightInfoDistantEXT sun = {};
  sun.sType = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DISTANT_EXT;
  const float length = sqrtf(0.4f * 0.4f + 0.8f * 0.8f + 0.3f * 0.3f);
  sun.direction = { 0.4f / length, -0.8f / length, 0.3f / length };
  sun.angularDiameterDegrees = 0.5f;
  sun.volumetricRadianceScale = 1.0f;
  remixapi_LightInfo lightInfo = {};
  lightInfo.sType = REMIXAPI_STRUCT_TYPE_LIGHT_INFO;
  lightInfo.pNext = &sun;
  lightInfo.hash = 0x99;
  lightInfo.radiance = { 40, 38, 32 };
  remixapi_LightHandle light = nullptr;
  api.CreateLight(&lightInfo, &light);

  const auto begin = std::chrono::steady_clock::now();
  int idleFrames = 0;
  uint32_t lastReported = UINT32_MAX;
  int exitCode = 0;

  for (int frame = 0;; ++frame) {
    const double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - begin).count();
    const uint32_t remaining = shaderCount();

    if (remaining != lastReported || frame % 120 == 0) {
      printf("precompile: %.0fs, %u shaders remaining\n", elapsed, remaining);
      fflush(stdout);
      lastReported = remaining;
    }

    // Wait for a stretch of frames without pending work, since new pipelines appear once rendering starts.
    idleFrames = remaining == 0 ? idleFrames + 1 : 0;
    if (idleFrames >= 180) {
      printf("precompile: done after %.0fs\n", elapsed);
      break;
    }
    if (elapsed > timeoutSeconds) {
      printf("precompile: timed out after %.0fs with %u shaders remaining\n", elapsed, remaining);
      exitCode = 1;
      break;
    }

    remixapi_CameraInfoParameterizedEXT camera = {};
    camera.sType = REMIXAPI_STRUCT_TYPE_CAMERA_INFO_PARAMETERIZED_EXT;
    const float forwardLength = sqrtf(0.04f + 1.0f);
    camera.position = { 0, 3, -9 };
    camera.forward = { 0, -0.2f / forwardLength, 1.0f / forwardLength };
    camera.up = { 0, 1, 0 };
    camera.right = { -1, 0, 0 };
    camera.fovYInDegrees = 70;
    camera.aspect = 1280.0f / 720.0f;
    camera.nearPlane = 0.1f;
    camera.farPlane = 1000.0f;
    remixapi_CameraInfo cameraInfo = {};
    cameraInfo.sType = REMIXAPI_STRUCT_TYPE_CAMERA_INFO;
    cameraInfo.pNext = &camera;
    cameraInfo.type = REMIXAPI_CAMERA_TYPE_WORLD;
    api.SetupCamera(&cameraInfo);

    remixapi_InstanceInfo instance = {};
    instance.sType = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO;
    instance.mesh = mesh;
    instance.transform = { { { 1, 0, 0, 0 }, { 0, 1, 0, 0 }, { 0, 0, 1, 0 } } };
    instance.doubleSided = 1;
    api.DrawInstance(&instance);
    api.DrawLightInstance(light);

    remixapi_SubmitInfo submit = remixapi_SubmitInfo_init();
    api.Submit(&submit);
  }

  api.Shutdown();
  return exitCode;
}
