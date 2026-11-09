# Giấy phép icon IntelliJ trong bản Kotlin

Rà ngày 09/11/2026 cho artifact `com.jetbrains.intellij.platform:icons:262.10968.63` (dùng qua `AllIconsKeys` của
Jewel). Câu hỏi: TableGrip (Apache‑2.0) có được phân phối lại các SVG này theo Apache‑2.0 không, như
`bundledLicenses` trong `app/build.gradle.kts` đang ghi?

## Kết luận

**Có, với hai ngoại lệ nhỏ.** Bộ icon là mã Apache‑2.0 của intellij-community. Gần như mọi SVG mang header Apache‑2.0
ngay trong file, và JetBrains đã nói công khai như vậy. Mục `"com.jetbrains.intellij.platform" to "Apache-2.0"` là
đúng; đã thêm comment ghi nguồn cạnh mục này.

Hai điểm còn lại (chi tiết ở phần "Rủi ro còn lại"):

1. `AllIconsKeys.Toolwindows.ToolWindowAskAI` (app dùng ở 3 chỗ) là một trong số ít file không có dòng Apache, chỉ có
   dòng copyright. Nó vẫn nằm dưới giấy phép Apache‑2.0 của repo tại thời điểm commit, nhưng hình dáng có thể gần với
   nhận diện của JetBrains AI. Chưa xác minh được.
2. Jar chứa vài ảnh logo/promo sản phẩm JetBrains. Apache‑2.0 không cấp quyền nhãn hiệu (mục 6). TableGrip không hiển
   thị các ảnh này, chỉ mang theo trong jar.

Kết luận này đã được áp dụng vào quyết định 2 trong [kế hoạch Rust UI kit](../rust-ui-kit.md): bản Rust dùng lại các
SVG có header Apache‑2.0, có ghi nguồn, và tự vẽ thay cho những file không có header.

## Bằng chứng

### 1. Header trong từng file SVG (nguồn chính)

Mở jar đang ship (`icons-262.10968.63.jar`, 3.662 file SVG) và đếm comment đầu file:

| Header | Số file |
|---|---|
| `Copyright 2000-YYYY JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.` | 2.025 |
| `Copyright 2000-YYYY JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.` | 1.454 |
| Bản "and contributors" của dòng trên | 26 |
| `Copyright © 2000–2024 JetBrains s.r.o.` (không nhắc giấy phép) | 25 |
| Không có header | 132 |

Tức 3.505/3.662 file (96%) tự ghi Apache‑2.0. Cùng header có trong nguồn, ví dụ
<https://github.com/JetBrains/intellij-community/blob/master/platform/icons/src/expui/general/add.svg>.

Trong 80 `AllIconsKeys` mà `app/src` dùng, chỉ `Toolwindows.ToolWindowAskAI` rơi vào nhóm không có dòng Apache (6 biến
thể, header `Copyright © 2000–2024 JetBrains s.r.o.`). Các icon còn lại đều có header Apache‑2.0.

### 2. Giấy phép của repo

- Thư mục nguồn: <https://github.com/JetBrains/intellij-community/tree/master/platform/icons>. Không có file license
  riêng; `OWNERSHIP` chỉ ghi `owner 'IJPL UI'` (nhóm phụ trách nội bộ, không phải giấy phép).
- `LICENSE.txt` gốc là toàn văn Apache License 2.0 cho đến commit
  [9691014c2d](https://github.com/JetBrains/intellij-community/commit/9691014c2d11e46a999c1ff04987498589b256bb)
  (10/06/2026, "something in legalese"), commit này thay bằng "JetBrains Open-Source Build Terms" 1.3.
- Bản Build Terms mới
  (<https://github.com/JetBrains/intellij-community/blob/master/LICENSE.txt>) không thu hồi Apache‑2.0. Nó điều chỉnh
  việc *dùng bản build* IntelliJ IDEA/PyCharm (dữ liệu cá nhân, telemetry, trọng tài) và mô tả các sản phẩm đó là
  "consist of open source software subject to the Apache 2.0 license". Mã nguồn, gồm cả SVG, vẫn mang header
  Apache‑2.0 trong file.
- Icon `toolWindowAskAI.svg` được thêm ở commit
  [97265aee33](https://github.com/JetBrains/intellij-community/commit/97265aee33725733c8499e438a236f2d38a104cd)
  (14/02/2025), khi `LICENSE.txt` của repo vẫn là toàn văn Apache 2.0.
- `NOTICE.txt` gốc (<https://github.com/JetBrains/intellij-community/blob/master/NOTICE.txt>) chỉ có:
  "This software includes code from IntelliJ IDEA, Copyright (C) JetBrains s.r.o.".

### 3. Metadata của artifact

POM (`icons-262.10968.63.pom`) không có `<licenses>`, chỉ trỏ `<url>` và `<scm>` về intellij-community. Jar không có
file LICENSE hay NOTICE. Đây là lý do nghi ngờ ban đầu; nó chỉ là thiếu metadata, không phải giấy phép khác. Giấy
phép nằm trong từng file SVG (mục 1).

### 4. Phát biểu công khai của JetBrains

- Blog chính thức JetBrains Platform, Yuriy Artamonov, 14/08/2024, "More New UI Icons for Your Plugins!":
  "Most icons are explicitly licensed under Apache 2.0 with the license included in the SVG file itself".
  <https://blog.jetbrains.com/platform/2024/08/more-new-ui-icons-for-your-plugins/>
- Diễn đàn hỗ trợ JetBrains, Dmitry Jemerov (nhân viên JetBrains), 12/02/2015: Community Edition "including the
  icons, is licensed under the Apache 2 license", trừ icon sản phẩm và ảnh splash/about vì chứa nhãn hiệu.
  <https://intellij-support.jetbrains.com/hc/en-us/community/posts/206110389>
- Hướng dẫn SDK "Working with Icons" khuyên plugin dùng lại icon nền tảng và trỏ tới
  <https://intellij-icons.jetbrains.design>, không nêu giấy phép riêng:
  <https://plugins.jetbrains.com/docs/intellij/icons.html>

Trang thương hiệu <https://www.jetbrains.com/company/brand/> và trang danh sách icon không đọc được nội dung qua công
cụ tải trang (render bằng JavaScript), nên không trích được quy định nào từ đó.

## Rủi ro còn lại

1. **`ToolWindowAskAI`.** Chỉ có dòng copyright, không có dòng Apache. Nó vẫn thuộc Apache‑2.0 theo `LICENSE.txt` của
   repo lúc thêm vào, nhưng không có khẳng định trong file. Hình dạng (ba nét bo tròn đan nhau) có thể được xem là
   gần nhận diện của sản phẩm JetBrains AI; chưa có nguồn xác nhận hay phủ nhận. Rủi ro thấp, nhưng là icon duy nhất
   app dùng mà không có header Apache. Cách loại bỏ hẳn: thay bằng một icon tự vẽ cho tab Agent.
2. **Logo sản phẩm trong jar.** Jar có `idea_logo_welcome.png`, `ultimate/ideaUltimatePromo.svg`,
   `ultimate/pycharmPromo.svg`, `codeWithMe/cwmLogoToolbar.svg`, `welcome/learnTab/jetBrainsAcademy.svg`… Apache‑2.0
   mục 6 không cho quyền dùng nhãn hiệu. TableGrip không hiển thị các file này nên không "dùng" nhãn hiệu; chỉ cần
   không bao giờ đưa chúng lên giao diện.
3. **NOTICE.** Apache‑2.0 mục 4(d) yêu cầu chuyển tiếp file NOTICE *đi kèm Work được phân phối*. Artifact không kèm
   NOTICE nên về mặt chữ không bắt buộc. Nếu muốn cẩn thận, có thể thêm dòng của `NOTICE.txt` intellij-community vào
   `THIRD-PARTY-NOTICES.txt`. Chưa làm (ngoài phạm vi việc này).

## Thay đổi mã

Chỉ thêm comment ghi nguồn cạnh `"com.jetbrains.intellij.platform" to "Apache-2.0"` trong `app/build.gradle.kts`.
Không đổi giá trị, không đổi hành vi build.
