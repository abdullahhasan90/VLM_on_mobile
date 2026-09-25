# PowerShell script to push Gemma 4 model to connected Android device
$ADB = "C:\Users\abdul\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$MODEL_SRC = "C:\dev\VLM_on_mobile\models\gemma-4-E4B-it.litertlm"
$PACKAGE_NAME = "com.example.vlm_on_mobile"
$DEST_DIR = "/sdcard/Android/data/$PACKAGE_NAME/files"

if (-not (Test-Path $MODEL_SRC)) {
    Write-Host "Error: Local model file not found at $MODEL_SRC" -ForegroundColor Red
    exit 1
}

Write-Host "Checking connected ADB devices..." -ForegroundColor Yellow
& $ADB devices

Write-Host "Creating target directory on device..." -ForegroundColor Yellow
& $ADB shell "mkdir -p $DEST_DIR"

Write-Host "Pushing $MODEL_SRC (~3.4 GB) to $DEST_DIR ... Please wait." -ForegroundColor Cyan
& $ADB push $MODEL_SRC $DEST_DIR/gemma-4-E4B-it.litertlm

Write-Host "Done! Verification:" -ForegroundColor Green
& $ADB shell "ls -lh $DEST_DIR/gemma-4-E4B-it.litertlm"
