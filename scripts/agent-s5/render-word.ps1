param([Parameter(Mandatory=$true)][string]$Directory)
$ErrorActionPreference='Stop'
$visualRoot=(Resolve-Path -LiteralPath $Directory).Path
$word=New-Object -ComObject Word.Application
$completed=@()
$cleanupError=$null
try {
    $word.Visible=$false
    $word.DisplayAlerts=0
    $word.AutomationSecurity=3
    foreach($name in @('poi-original','poi-revised')) {
        $inputPath=Join-Path $visualRoot ($name+'.docx')
        $document=$word.Documents.Open($inputPath,$false,$true)
        try { $document.ExportAsFixedFormat((Join-Path $visualRoot ($name+'.pdf')),17); $completed+=$name }
        finally {$document.Close(0)}
    }
} finally {
    try {$word.Quit()} catch {$cleanupError=$_.Exception.GetType().Name}
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word)
    @{transport='Word.Application COM ExportAsFixedFormat';rendererIdentity='SEE_PDF_CREATOR_METADATA';completed=$completed;cleanupError=$cleanupError} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $visualRoot 'word-render-evidence.json') -Encoding UTF8
}
