# Regenerates greppable pretty-printed copies of the minified extension bundle fixtures.
# Run after adding or refreshing any bundle fixture:
#   powershell -File tools\prettify-bundles.ps1
# The .pretty.js copies are development aids only: never load them in tests, never
# checksum them; the verified minified fixtures remain the single source of truth.
$fixtures = "core\build\fixture-cache"
$out = "tools\bundle-pretty"
if (-not (Test-Path $fixtures) -or -not (Get-ChildItem $fixtures -Filter "*.js" -ErrorAction SilentlyContinue)) {
    Write-Host "No cached fixtures found in $fixtures. Run .\gradlew.bat :core:jvmTest first to populate the cache."
    exit 1
}
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem $fixtures -Filter "*.js" | Where-Object { $_.Name -notlike "*.pretty.js" } | ForEach-Object {
    $dest = Join-Path $out ($_.BaseName + ".pretty.js")
    npx --yes js-beautify --file $_.FullName --outfile $dest --indent-size 2
    "wrote $dest"
}
