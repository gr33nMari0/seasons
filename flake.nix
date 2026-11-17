# flake.nix
{
  description = "Seasons dev shell";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-darwin" ];
      forAll = f: nixpkgs.lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s});
    in {
      devShells = forAll (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.jdk25 pkgs.nodejs_24 pkgs.git pkgs.gh ];
          JAVA_HOME = "${pkgs.jdk25}";
        };
      });
    };
}
