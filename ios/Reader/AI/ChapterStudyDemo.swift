#if DEBUG
import Foundation

extension ChapterStudyModel {
    /// Yalnızca hata ayıklama: API'siz arayüz denemesi için hazır yanıtla model.
    static func demo(book: Book) -> ChapterStudyModel {
        let m = ChapterStudyModel(title: "Chapter 3: Pointers", text: "x", book: book,
                                  config: LLMConfig(provider: .claude, model: "demo"), scopeKey: "demo")
        let json = """
        Burada JSON:
        ```json
        {"summary":["**Pointer**, bir değişkenin bellek adresini tutar.","`*p` ile adresteki değere erişilir."],
         "concepts":[{"term":"Dereference","definition":"Pointer'ın gösterdiği değeri okumak."}],
         "quiz":[{"question":"`int *p = &x;` satırında `p` neyi tutar?","options":["x'in değerini","x'in adresini","p'nin adresini","Hiçbirini"],"answer":1,"explanation":"`&x` adresi verir, p bu adresi saklar."},
                 {"question":"`*p = 5;` ne yapar?","options":["p'yi 5 yapar","x'i 5 yapar","Hata verir","Adresi değiştirir"],"answer":1,"explanation":"Dereference ile gösterilen değişken değişir."}],
         "qa":[{"question":"Neden dangling pointer tehlikelidir?","answer":"Serbest bırakılmış belleği gösterir; okuma/yazma tanımsız davranıştır."}]}
        ```
        """
        m.data = ChapterStudyModel.parse(json)
        return m
    }
}
#endif
