package net.dixq.partyslideshow

/**
 * ファイル名から投稿者名を抽出するユーティリティオブジェクト
 */
object UploaderNameParser {
    fun parse(fileName: String): String? {
        val namePart = fileName.substringBeforeLast('.', fileName)
        if (namePart.contains(" - ")) {
            val potentialTimestamp = namePart.substringAfterLast(" - ", "")
            if (potentialTimestamp.matches(Regex("^\\d{8}_\\d{6}$"))) return null
        }
        val firstSpaceIndex = namePart.indexOf(' ')
        if (firstSpaceIndex != -1 && firstSpaceIndex < namePart.length - 1) {
            val potentialName = namePart.substring(firstSpaceIndex + 1).trim()
            if (potentialName.isNotEmpty()) return potentialName
        }
        return null
    }
}
