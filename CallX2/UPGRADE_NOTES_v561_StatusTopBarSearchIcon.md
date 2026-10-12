# v561 — Status tab: search bar -> search icon, "Status" title top row me

- `fragment_status.xml`: poori width wali pill search bar hata ke ek top row: "Status" title (left) + search icon + privacy icon. Ek poori row ki height bachi, carousel/list ko zyada jagah.
- Search icon (`btn_status_search_open`) tap -> usi row me inline field (`fl_status_search`) khulta hai, title + icon chhup jaate hain, keyboard aata hai.
- Field ke andar close button (`btn_status_search_clear`): pehla tap text clear, text khali ho to doosra tap field band karke title + icon wapas.
- `item_status_carousel.xml`: list ke andar wala "Status" title hata (ab top row me); `CarouselVH` null-safe hai, koi change nahi chahiye.
- `StatusFragment`: ids `et_status_search` / `btn_status_search_clear` wahi, search/filter logic same. Title par TalkBack heading + 1.3x font-scale cap yahin shift.
- Behaviour: search close karne par query clear ho jaati hai (list filter hat jaata hai).
- Check: privacy icon, pull-to-refresh, FAB/archive buttons, keyboard open/close, rotate/tablet.
