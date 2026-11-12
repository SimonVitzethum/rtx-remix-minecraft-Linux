{
  description = "Native Linux Minecraft 26.3 RTX Remix development tools";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forSystems = nixpkgs.lib.genAttrs systems;
    in {
      devShells = forSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          javaTools = with pkgs; [ jdk25 git cacert curl unzip zip python3 ];
          nativeTools = with pkgs; [
            cmake meson ninja pkg-config glslang spirv-tools shader-slang
            vulkan-headers vulkan-loader libglvnd libx11 libxcb wayland sdl3 zlib
          ];
          javaEnvironment = {
            JAVA_HOME = pkgs.jdk25.home;
            JAVA25_HOME = pkgs.jdk25.home;
          };
        in {
          default = pkgs.mkShellNoCC (javaEnvironment // {
            name = "rtx-remix-java";
            packages = javaTools;
          });

          native = pkgs.mkShell (javaEnvironment // {
            name = "rtx-remix-native-development";
            packages = javaTools ++ nativeTools;
          });
        });

      formatter = forSystems (system: nixpkgs.legacyPackages.${system}.nixfmt);
    };
}
