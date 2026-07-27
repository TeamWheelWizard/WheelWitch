{
  description = "WheelWitch – Retro Rewind Mario Kart Wii Pack manager for Android";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs =
    { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
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
      devShells.${system}.default = pkgs.mkShell {
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

          just -l
        '';
      };
    };
}
