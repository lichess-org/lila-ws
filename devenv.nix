{
  pkgs,
  lib,
  config,
  ...
}:
{
  languages = {
    java = {
      enable = true;
    };
    scala = {
      enable = true;
      sbt.enable = true;
    };
  };

  packages = [
    pkgs.graalvmPackages.graalvm-ce
  ];

  processes.lila-ws = {
    exec = ''
      if [ -f "$DEVENV_ROOT/src/main/scala/LilaWs.scala" ]; then
        cd "$DEVENV_ROOT"
      else
        dir="''${LILA_WS_PATH:-$DEVENV_ROOT/../lila-ws}"
        cd "$dir" || { echo "lila-ws checkout not found at $dir; clone it there or set LILA_WS_PATH" >&2; exit 1; }
      fi
      exec ${config.languages.scala.sbt.package}/bin/sbt run
    '';
  };
}
