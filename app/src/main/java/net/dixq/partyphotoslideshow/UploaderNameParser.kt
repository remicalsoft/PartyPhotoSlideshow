package net.dixq.partyphotoslideshow

/**
 * ファイル名から投稿者名を抽出するユーティリティオブジェクト
 */
object UploaderNameParser {
    /**
     * ファイル名から投稿者名を抽出する。
     * "タイムスタンプ 名前.jpg" や "元のファイル名 名前 名字.jpg" の形式に対応。
     * @return 抽出した名前。一時ファイルや命名規則に合わない場合はnull。
     */
    fun parse(fileName: String): String? {
        val namePart = fileName.substringBeforeLast('.', fileName)

        // 1. Dropboxが生成する一時ファイル ("名前 - 元のファイル名.jpg" など) は無視する。
        //    この形式は、ファイル名に " - " を含むという特徴がある。
        if (namePart.contains(" - ")) {
            return null
        }

        // 2. 最終的なファイル名から名前を抽出する。
        //    形式: "元のファイル名 名前.jpg" または "タイムスタンプ 名前.jpg"
        //    最初のスペース以降を名前と見なす。
        val firstSpaceIndex = namePart.indexOf(' ')
        if (firstSpaceIndex != -1 && firstSpaceIndex < namePart.length - 1) {
            val potentialName = namePart.substring(firstSpaceIndex + 1).trim()
            // 抽出した名前が空でないことを確認
            if (potentialName.isNotEmpty()) {
                return potentialName
            }
        }

        return null // パターンに一致しない場合は名前なし
    }
}
