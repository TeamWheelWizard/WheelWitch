{
  pkgs,
  config,
  lib,
  ...
}: {
  assertions = [
    {
      assertion = pkgs.stdenv.hostPlatform.system == "x86_64-linux";
      message = "WheelWitch dev environment supports x86_64-linux only; Android build tools (aapt2) are broken on ARM.";
    }
  ];

  android = {
    enable = true;
    platforms.version = ["36.1"];
    buildTools.version = ["36.0.0"];
    emulator.enable = false;
    systemImages.enable = false;
    ndk.enable = false;
    sources.enable = false;
  };

  languages.java.jdk.package = pkgs.jdk21;

  packages = with pkgs; [
    android-tools
    just
  ];

  env = {
    ANDROID_SDK_ROOT = config.env.ANDROID_HOME;
    ORG_GRADLE_PROJECT_android_sdk_skipSdkInstall = "true";
    KEYSTORE_PATH = config.secretspec.secrets.KEYSTORE_PATH or "";
    KEYSTORE_PASSWORD = config.secretspec.secrets.KEYSTORE_PASSWORD or "";
    KEY_ALIAS = config.secretspec.secrets.KEY_ALIAS or "";
    KEY_PASSWORD = config.secretspec.secrets.KEY_PASSWORD or "";
  };

  enterShell = lib.mkAfter ''
    echo ""
    echo -e "\033[1mWheel Witch\033[0m"
    echo -e "  \033[2mjava:\033[0m         $(java -version 2>&1 | head -1)"
    echo -e "  \033[2mANDROID_HOME:\033[0m $ANDROID_HOME"
    echo ""

    just -l
  '';
}
