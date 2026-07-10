# Regenerates greppable pretty-printed copies of the minified extension bundle fixtures.
# Run after adding or refreshing any bundle fixture:
#   powershell -File tools\prettify-bundles.ps1
# The .pretty.js copies are development aids only: never load them in tests, never
# checksum them; the verified minified fixtures remain the single source of truth.
$fixtures = "core\src\jvmTest\resources\fixtures"
$out = "tools\bundle-pretty"
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem $fixtures -Filter "*.js" | Where-Object { $_.Name -notlike "*.pretty.js" } | ForEach-Object {
    $dest = Join-Path $out ($_.BaseName + ".pretty.js")
    npx --yes js-beautify --file $_.FullName --outfile $dest --indent-size 2
    "wrote $dest"
}
