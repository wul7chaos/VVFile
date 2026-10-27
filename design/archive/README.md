# 压缩包测试样本

`app/src/test/resources/archives/` 里的 9 个文件都是**测试样本**，不参与 App 构建，
单测用它们验证 ZIP / 7z / RAR 的读取、加密与解密（见 `EncryptedZipTest`、
`SevenZipArchiveTest`、`RarArchiveTest`、`ArchiveSignatureTest`、
`ArchiveEncryptionTest`）。

| 文件 | 类型 | 内容 | 口令 |
| --- | --- | --- | --- |
| `zipcrypto.zip` | ZIP，传统加密（ZipCrypto） | `a.txt`（17 B）、`docs/b.txt`（500 B） | `123456` |
| `aes.zip` | ZIP，WinZip AES-256 | 同上 | `123456` |
| `plain.7z` | 7z，无密码 | 同上 | — |
| `encrypted.7z` | 7z，连文件头加密（`-mhe=on`） | 同上 | `123456` |
| `dataonly.7z` | 7z，只加密数据 | 同上 | `123456` |
| `rar5.rar` | RAR5，无密码 | 同上 | — |
| `solid5.rar` | RAR5，固实压缩（`-s`） | 同上（后续条目要压着前一条解） | — |
| `rar5-pw.rar` | RAR5，只加密数据（`-p`） | 同上 | `123456` |
| `rar5-pwh.rar` | RAR5，连文件头加密（`-hp`） | 同上 | `123456` |

自解压包不占样本位：测试里拿上面的归档临时拼一个「`MZ` + 几 KB 填充 + 归档体」，
WinRAR 生成的 `.exe` 就是这个结构，这样既覆盖了偏移修正，又不用往仓库里塞 500 KB 的 stub。

## 重新生成

7-Zip 的命令行就够，不需要装第三方 Python 包：

```bash
mkdir -p tmp/docs
printf 'hello from VVFile' > tmp/a.txt
python -c "open('tmp/docs/b.txt','w').write('x'*500)"
cd tmp

7z a -tzip -p123456 -mem=ZipCrypto ../zipcrypto.zip a.txt docs/b.txt
7z a -tzip -p123456 -mem=AES256    ../aes.zip      a.txt docs/b.txt
7z a -t7z                          ../plain.7z     a.txt docs/b.txt
7z a -t7z -p123456 -mhe=on         ../encrypted.7z a.txt docs/b.txt
7z a -t7z -p123456                 ../dataonly.7z  a.txt docs/b.txt
```

RAR 只能用 WinRAR 的 `Rar.exe`（7-Zip 只能解、不能生成 RAR）：

```bash
rar a -ma5 ../rar5.rar      a.txt docs/b.txt
rar a -ma5 -s ../solid5.rar a.txt docs/b.txt
rar a -ma5 -p123456  ../rar5-pw.rar  a.txt docs/b.txt
rar a -ma5 -hp123456 ../rar5-pwh.rar a.txt docs/b.txt
```

WinRAR 7.x 只能新建 RAR5，RAR4 样本得用老版本或现成的包；`junrar` 两条格式都支持，
单测的 RAR 用例只跑 RAR5 也不影响结论。

`make_encrypted_zip.py` 是最早手写的 ZipCrypto 生成器（不依赖 7-Zip），
现在留作算法对照：它和 `ZipCrypto.kt` 用的是同一套密钥流，两边互相验证过。

## 样本刻意保持很小

每个都在 140–410 字节：单测会复制到临时目录再解开，体积小一点跑得快；
同时覆盖了两条容易写错的路径——`a.txt` 太小，7-Zip 会**存储**（实际压缩方式 0）
而不是 deflate；`docs/b.txt` 才会走 deflate（压缩方式 8）。AES 的「真实压缩方式」
就藏在额外字段 `0x9901` 里，两个值都得认。
