# 压缩包测试样本

`app/src/test/resources/archives/` 里的 5 个文件都是**测试样本**，不参与 App 构建，
单测用它们验证 ZIP / 7z 的加密与解密（见 `EncryptedZipTest`、`SevenZipArchiveTest`、
`ArchiveEncryptionTest`）。

| 文件 | 类型 | 内容 | 口令 |
| --- | --- | --- | --- |
| `zipcrypto.zip` | ZIP，传统加密（ZipCrypto） | `a.txt`（17 B）、`docs/b.txt`（500 B） | `123456` |
| `aes.zip` | ZIP，WinZip AES-256 | 同上 | `123456` |
| `plain.7z` | 7z，无密码 | 同上 | — |
| `encrypted.7z` | 7z，连文件头加密（`-mhe=on`） | 同上 | `123456` |
| `dataonly.7z` | 7z，只加密数据 | 同上 | `123456` |

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

`make_encrypted_zip.py` 是最早手写的 ZipCrypto 生成器（不依赖 7-Zip），
现在留作算法对照：它和 `ZipCrypto.kt` 用的是同一套密钥流，两边互相验证过。

## 样本刻意保持很小

每个都在 200–410 字节：单测会复制到临时目录再解开，体积小一点跑得快；
同时覆盖了两条容易写错的路径——`a.txt` 太小，7-Zip 会**存储**（实际压缩方式 0）
而不是 deflate；`docs/b.txt` 才会走 deflate（压缩方式 8）。AES 的「真实压缩方式」
就藏在额外字段 `0x9901` 里，两个值都得认。
