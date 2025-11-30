package io.github.shangor.lan.transfer.ui.common;

import javax.swing.JProgressBar;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;

public class ProgressCellRenderer extends JProgressBar implements TableCellRenderer {

    public ProgressCellRenderer() {
        super(0, 100);
        setStringPainted(true);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        if (value instanceof Number number) {
            int v = (int) Math.round(number.doubleValue());
            setValue(v);
            setString(v + "%");
        } else {
            setValue(0);
            setString("0%");
        }
        if (isSelected) {
            setBackground(table.getSelectionBackground());
        } else {
            setBackground(table.getBackground());
        }
        return this;
    }
}
