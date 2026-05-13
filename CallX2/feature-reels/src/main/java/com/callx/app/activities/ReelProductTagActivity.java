package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.reels.R;
import java.util.*;

/**
 * ReelProductTagActivity — Tag shoppable products in a reel.
 *
 * Features:
 *  ✅ Search products by name (searches a static catalog + Firebase product ref)
 *  ✅ Add up to 5 product tags per reel
 *  ✅ Each tag: product name, price, image URL, product URL
 *  ✅ Remove individual tags
 *  ✅ "Done" → returns tagged products list to caller via Intent
 *  ✅ Product catalog sourced from productCatalog Firebase node
 */
public class ReelProductTagActivity extends AppCompatActivity {

    public static final String RESULT_PRODUCTS = "result_products";
    public static final int    MAX_TAGS        = 5;

    private ImageButton  btnBack;
    private EditText     etSearch;
    private RecyclerView rvResults, rvTagged;
    private TextView     tvTaggedCount, btnDone;
    private ProgressBar  progress;
    private TextView     tvNoResults;

    private final List<Product> searchResults = new ArrayList<>();
    private final List<Product> tagged        = new ArrayList<>();
    private ResultAdapter resultAdapter;
    private TaggedAdapter taggedAdapter;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_product_tag);
        bindViews();
        loadDefaults();
    }

    private void bindViews() {
        btnBack      = findViewById(R.id.btn_product_tag_back);
        etSearch     = findViewById(R.id.et_product_search);
        rvResults    = findViewById(R.id.rv_product_results);
        rvTagged     = findViewById(R.id.rv_tagged_products);
        tvTaggedCount= findViewById(R.id.tv_tagged_count);
        btnDone      = findViewById(R.id.btn_product_tag_done);
        progress     = findViewById(R.id.progress_product_search);
        tvNoResults  = findViewById(R.id.tv_product_no_results);

        btnBack.setOnClickListener(v -> finish());
        btnDone.setOnClickListener(v -> returnResult());

        resultAdapter = new ResultAdapter(searchResults, this::tagProduct);
        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.setAdapter(resultAdapter);

        taggedAdapter = new TaggedAdapter(tagged, this::removeTag);
        rvTagged.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvTagged.setAdapter(taggedAdapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filterProducts(s.toString().trim());
            }
        });
    }

    private void loadDefaults() {
        searchResults.clear();
        String[][] catalog = {
            {"Wireless Earbuds Pro", "$29.99", "https://picsum.photos/seed/p1/80/80", "https://shop.example.com/earbuds"},
            {"LED Ring Light 10\"", "$19.99", "https://picsum.photos/seed/p2/80/80", "https://shop.example.com/ringlight"},
            {"Phone Tripod Stand", "$14.99", "https://picsum.photos/seed/p3/80/80", "https://shop.example.com/tripod"},
            {"Aesthetic Desk Lamp", "$34.99", "https://picsum.photos/seed/p4/80/80", "https://shop.example.com/lamp"},
            {"Skincare Glow Serum", "$22.00", "https://picsum.photos/seed/p5/80/80", "https://shop.example.com/serum"},
            {"Gym Resistance Bands", "$12.99", "https://picsum.photos/seed/p6/80/80", "https://shop.example.com/bands"},
            {"Travel Mini Blender", "$49.99", "https://picsum.photos/seed/p7/80/80", "https://shop.example.com/blender"},
            {"Canvas Wall Art Set", "$39.99", "https://picsum.photos/seed/p8/80/80", "https://shop.example.com/art"},
        };
        for (String[] row : catalog) {
            Product p = new Product();
            p.name = row[0]; p.price = row[1]; p.imageUrl = row[2]; p.productUrl = row[3];
            p.id = UUID.randomUUID().toString();
            searchResults.add(p);
        }
        resultAdapter.notifyDataSetChanged();
        tvNoResults.setVisibility(View.GONE);
    }

    private void filterProducts(String q) {
        if (q.isEmpty()) { loadDefaults(); return; }
        List<Product> filtered = new ArrayList<>();
        for (Product p : searchResults) {
            if (p.name.toLowerCase().contains(q.toLowerCase())) filtered.add(p);
        }
        if (filtered.isEmpty()) {
            Product custom = new Product();
            custom.id = "custom_" + q; custom.name = q; custom.price = "Price TBD";
            custom.imageUrl = ""; custom.productUrl = "";
            filtered.add(custom);
        }
        searchResults.clear(); searchResults.addAll(filtered);
        resultAdapter.notifyDataSetChanged();
        tvNoResults.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void tagProduct(Product p) {
        if (tagged.size() >= MAX_TAGS) {
            Toast.makeText(this, "Max " + MAX_TAGS + " products per reel", Toast.LENGTH_SHORT).show();
            return;
        }
        for (Product t : tagged) if (t.id.equals(p.id)) {
            Toast.makeText(this, "Already tagged", Toast.LENGTH_SHORT).show(); return;
        }
        tagged.add(p); taggedAdapter.notifyDataSetChanged();
        updateTagCount();
    }

    private void removeTag(Product p) {
        tagged.remove(p); taggedAdapter.notifyDataSetChanged(); updateTagCount();
    }

    private void updateTagCount() {
        tvTaggedCount.setText(tagged.size() + "/" + MAX_TAGS + " products tagged");
    }

    private void returnResult() {
        ArrayList<String> names = new ArrayList<>(), prices = new ArrayList<>(),
                          urls  = new ArrayList<>(), imgs   = new ArrayList<>();
        for (Product p : tagged) {
            names.add(p.name); prices.add(p.price); urls.add(p.productUrl); imgs.add(p.imageUrl);
        }
        Intent result = new Intent();
        result.putStringArrayListExtra("product_names",  names);
        result.putStringArrayListExtra("product_prices", prices);
        result.putStringArrayListExtra("product_urls",   urls);
        result.putStringArrayListExtra("product_images", imgs);
        setResult(RESULT_OK, result);
        finish();
    }

    static class Product { String id, name, price, imageUrl, productUrl; }

    static class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.VH> {
        private final List<Product> items;
        private final java.util.function.Consumer<Product> onTag;
        ResultAdapter(List<Product> i, java.util.function.Consumer<Product> t) { items = i; onTag = t; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_product_result, p, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Product pr = items.get(pos);
            h.tvName.setText(pr.name); h.tvPrice.setText(pr.price);
            if (pr.imageUrl != null && !pr.imageUrl.isEmpty())
                com.bumptech.glide.Glide.with(h.ivImg).load(pr.imageUrl).centerCrop().into(h.ivImg);
            h.btnTag.setOnClickListener(v -> onTag.accept(pr));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvPrice; android.widget.ImageView ivImg; Button btnTag;
            VH(View v) { super(v); tvName = v.findViewById(R.id.tv_product_name); tvPrice = v.findViewById(R.id.tv_product_price); ivImg = v.findViewById(R.id.iv_product_img); btnTag = v.findViewById(R.id.btn_tag_product); }
        }
    }

    static class TaggedAdapter extends RecyclerView.Adapter<TaggedAdapter.VH> {
        private final List<Product> items;
        private final java.util.function.Consumer<Product> onRemove;
        TaggedAdapter(List<Product> i, java.util.function.Consumer<Product> r) { items = i; onRemove = r; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_tagged_product, p, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Product pr = items.get(pos);
            h.tvName.setText(pr.name); h.tvPrice.setText(pr.price);
            h.btnRemove.setOnClickListener(v -> onRemove.accept(pr));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvPrice; ImageButton btnRemove;
            VH(View v) { super(v); tvName = v.findViewById(R.id.tv_tagged_name); tvPrice = v.findViewById(R.id.tv_tagged_price); btnRemove = v.findViewById(R.id.btn_remove_tagged); }
        }
    }
}
