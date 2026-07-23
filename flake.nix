{
  description = "WheelWitch – Retro Rewind Mario Kart Wii Pack manager for Android";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
  };

  outputs =
    inputs @ { flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [ "x86_64-linux" "aarch64-linux" ];

      perSystem =
        { config, self', pkgs, system, ... }:
        let
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "36" ];
            buildToolsVersions = [ "36.0.0" ];
            includeEmulator = false;
            includeSources = false;
            includeSystemImages = false;
          };
          androidHome = "${android.androidsdk}/libexec/android-sdk";
        in
        {
          _module.args.pkgs = import inputs.nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          devShells.default = pkgs.mkShell {
            name = "wheelwitch";

            packages = with pkgs; [
              jdk21
              android-tools
              android.androidsdk
            ];

            ANDROID_SDK_ROOT = androidHome;
            ANDROID_HOME = androidHome;
            ORG_GRADLE_PROJECT_android_sdk_skipSdkInstall = "true";
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidHome}/build-tools/36.0.0/aapt2";

            shellHook = ''
              echo ""
              echo -e "\033[1mWheel Witch\033[0m"
              echo -e "  \033[2mjava:\033[0m         $(java -version 2>&1 | head -1)"
              echo -e "  \033[2mANDROID_HOME:\033[0m $ANDROID_HOME"
              echo ""

              if [ "$(uname -m)" = "aarch64" ] && [ ! -f /proc/sys/fs/binfmt_misc/qemu-x86_64 ] 2>/dev/null; then
                echo -e "\033[33m⚠ aarch64: x86_64 binary emulation (binfmt_misc) not configured.\033[0m" >&2
                echo "   Android build tools (aapt2) are x86_64-only and won't run." >&2
                echo "   Add to your NixOS config:" >&2
                echo '     boot.binfmt.emulatedSystems = [ "x86_64-linux" ];' >&2
                echo "" >&2
              fi

              just -l
            '';
          };
        };
    };
}
