# Code borrowed by Reikai JP's Japanese reader

Reikai JP is GPL-3.0-or-later. Its Japanese reading mode (the page script in
`app/src/main/assets/jp-reader/`, the statistics in `app/src/main/java/jp/reikai/stats/`) and its
chapter translation and EPUB export (`app/src/main/java/jp/reikai/translate/`,
`app/src/main/java/jp/reikai/export/`) borrow from the projects below. Each borrowed part is marked
in its file. A copy of this notice ships inside the app, at `assets/jp-reader/NOTICE.txt`.

- **ッツ Ebook Reader** (<https://github.com/ttu-ttu/ebook-reader>), BSD-3-Clause: the character
  count, the furigana modes, the reading statistics and their export format. Its licence:

```
BSD 3-Clause License

Copyright (c) 2024, ッツ Reader Authors
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

- **chimahon** (<https://github.com/Chimahon/chimahon>), GPL-3.0, and **Hoshi Reader**
  (<https://github.com/Manhhao/Hoshi-Reader>), Copyright (c) 2026 Manhhao, GPL-3.0-or-later: how
  pages are laid out and turned in an Android WebView. Same licence family as Reikai JP (text in
  `LICENSE`).
- **Tsundoku** (<https://github.com/tsundoku-otaku/tsundoku>), Apache-2.0: the requests of the
  translation engines and the EPUB writer, modified. Licence text in `LICENSES/Apache-2.0.txt`.
