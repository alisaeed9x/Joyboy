cd ~/Subtitler
git checkout -b ir713
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
unzip -o ~/storage/downloads/Subtitler_v713.zip -d .
git add -A && git commit -m "v713 IR remote" && git push -u origin ir713
