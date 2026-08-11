# Production-mode smoke test: launch the final uranus jar via fabric-loader's KnotServer,
# exactly as a real production install would (mods in run/mods, no dev classpath, no
# architectury-transformer agent, fabric.development=false).
$ErrorActionPreference = 'Stop'

$cp = (Get-Content 'D:\aiminecraftdev\Uranus\fabric\build\prod-classpath.txt' | Where-Object { $_.Trim() -ne '' }) -join ';'
$mcJar = 'D:\aiminecraftdev\Uranus\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-8943ec45d3\26.2\minecraft-merged-8943ec45d3-26.2.jar'

Write-Host "== Production KnotServer smoke test =="
Write-Host "Classpath entries: $((($cp -split ';') | Where-Object { $_ }).Count)"
Write-Host "Mods: $((Get-ChildItem 'D:\aiminecraftdev\Uranus\fabric\run\mods' -Filter '*.jar' | Select-Object -ExpandProperty Name) -join ', ')"
Write-Host "Working dir: D:\aiminecraftdev\Uranus\fabric\run"

$gameJarProp = "-Dfabric.gameJarPath=$mcJar"
$javaArgs = @(
  '-cp', $cp,
  $gameJarProp,
  '-Dfabric.development=false',
  'net.fabricmc.loader.launch.knot.KnotServer',
  '--nogui'
)

Push-Location 'D:\aiminecraftdev\Uranus\fabric\run'
try {
  & 'C:\Program Files\Zulu\zulu-25\bin\java.exe' @javaArgs 2>&1 | Tee-Object -FilePath 'D:\aiminecraftdev\Uranus\fabric\run-prod-server.log'
  $code = $LASTEXITCODE
  Write-Host "`n== KnotServer exited with code $code =="
  exit $code
} finally {
  Pop-Location
}
