package com.nekkora.agent.ui;

import com.nekkora.tokens.Radius;
import com.nekkora.ui.brand.NekkoraLogo;
import com.nekkora.ui.dialog.NekkoraDialog;
import com.nekkora.ui.theme.Scale;
import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.geom.RoundRectangle2D;

/**
 * Diálogo Nekkora que não ultrapassa a área útil da tela (escala de 200% em telas pequenas): limita
 * a altura e a largura antes de aparecer; o conteúdo deve estar em um painel com rolagem.
 */
class FittedDialog extends NekkoraDialog {

    FittedDialog(Component parent, String title) {
        super(parent, title);
        setIconImages(NekkoraLogo.appIconImages());
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible) clampToScreen();
        super.setVisible(visible);
    }

    private void clampToScreen() {
        GraphicsConfiguration gc = getOwner() != null && getOwner().isShowing() ? getOwner().getGraphicsConfiguration() : getGraphicsConfiguration();
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        int maxW = b.width - in.left - in.right - 16;
        int maxH = b.height - in.top - in.bottom - 16;
        if (getWidth() > maxW || getHeight() > maxH) {
            setSize(Math.min(getWidth(), maxW), Math.min(getHeight(), maxH));
            validate();
            // o NekkoraDialog recorta cantos arredondados no tamanho anterior: refaz no novo
            if (getShape() != null) {
                float r = Scale.px(Radius.LARGE) * 2f;
                setShape(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), r, r));
            }
        }
        Rectangle r = getBounds();
        int x = Math.max(b.x + in.left, Math.min(r.x, b.x + b.width - in.right - r.width));
        int y = Math.max(b.y + in.top, Math.min(r.y, b.y + b.height - in.bottom - r.height));
        setLocation(x, y);
    }
}
