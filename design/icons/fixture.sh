#!/system/bin/sh
D=/sdcard/VVFile/图标预览
T=/sdcard/VVFile/icons-demo
rm -rf "$T"
if [ -d "$D" ]; then mv "$D" "$T"; fi
mkdir -p "$T/子目录"
cd "$T" || exit 1
for f in photo.jpg clip.mp4 song.mp3 app.apk pack.zip note.txt book.pdf doc.docx \
         data.xlsx deck.pptx Main.kt cfg.json run.sh notes.db font.ttf \
         blob.xyz noext .gitignore UP.PNG X.TAR.GZ; do
  [ -e "$f" ] || : > "$f"
done
ls -1 | wc -l
