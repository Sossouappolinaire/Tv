package bj.radio;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;
        int p = (int) (24 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(p, p, p, p);

        TextView titre = new TextView(this);
        titre.setText("Radio & TV du monde");
        titre.setTextSize(28);
        titre.setTypeface(null, Typeface.BOLD);
        titre.setGravity(Gravity.CENTER);
        titre.setPadding(0, 0, 0, p * 2);
        root.addView(titre);

        root.addView(bouton("📻  Radio", RadioActivity.class, d));
        root.addView(bouton("📺  Télévision", TvActivity.class, d));

        setContentView(root);
    }

    private Button bouton(String texte, Class<?> cible, float d) {
        Button bt = new Button(this);
        bt.setText(texte);
        bt.setTextSize(24);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (96 * d));
        lp.bottomMargin = (int) (16 * d);
        bt.setLayoutParams(lp);
        bt.setOnClickListener(v -> startActivity(new Intent(this, cible)));
        return bt;
    }
}
