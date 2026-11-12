{
  description = "Native Linux RTX Remix runtime development tools";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forSystems = nixpkgs.lib.genAttrs systems;
    in {
      devShells = forSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
        in {
          default = pkgs.mkShell {
            name = "rtx-remix-runtime-development";
            packages = with pkgs; [
              git cacert curl unzip zip python3 cmake meson ninja pkg-config
              glslang spirv-tools shader-slang vulkan-headers vulkan-loader
              libglvnd libx11 libxcb wayland wayland-protocols sdl3 zlib
            ];
          };
        });

      formatter = forSystems (system: nixpkgs.legacyPackages.${system}.nixfmt);
    };
}
