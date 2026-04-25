@echo off
setlocal

pushd "%cd%"

cd /d "Z:\3d\GIT\FT8CN\ft8cn"

git add -A
git commit -m "Update code"
git push

popd
endlocal
pause