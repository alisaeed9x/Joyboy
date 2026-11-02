cd ~/Subtitler
git checkout ir713 2>/dev/null || git checkout -b ir713
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
unzip -o ~/storage/downloads/Subtitler_v714.zip -d .
git add -A && git commit -m "v714 IR remote - batch search" && git push -u origin ir713
