package com.equensworldline.amq.utils.gui;

import javax.swing.*;
import javax.swing.table.TableModel;
import java.awt.*;

public class ShowTable extends JFrame {
    //empty headers for the table
    private static String[] columns = new String[]{};

    //empty data for the table in a 2d array
    private static Object[][] data = new Object[][]{{}};

    private static int x = 0;
    private static int y = 0;
    private int INITIAL_WIDTH = 1024;
    private int cols = 0;
    private int rows = 0;
    private JTable table;
    private JScrollPane scrollPane;

    public ShowTable() {
        this("New Table", columns, data);
    }

    public ShowTable(String title) {
        this(title, columns, data);
    }

    public ShowTable(String title, String[] columns, Object[][] data) {
        //create table with data
        table = new JTable(data, columns);
        table.setAutoCreateRowSorter(true);

        //add the table to the frame
        scrollPane = new JScrollPane(table);
        this.add(scrollPane);

        this.setTitle(title);
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.pack();
        Dimension size = this.getBounds().getSize();
        size.setSize(INITIAL_WIDTH, size.getHeight());
        this.setSize(size);
        
        this.setVisible(true);
        this.setLocation(x, y);
        x = (x + 25) % 100;
        y = (y + 25) % 100;
        cols = columns.length;
        rows = data.length;
    }

    public void updateTitle(String title) {
        this.setTitle(title);
    }

    public void updateTable(String[] newColumns, Object[][] newData) {
        if (newData.length != table.getRowCount() || newColumns.length != table.getColumnCount()) {
            scrollPane.remove(table);
            this.remove(scrollPane);
            table = new JTable(newData, newColumns);
            table.setAutoCreateRowSorter(true);
            scrollPane = new JScrollPane(table);
            this.add(scrollPane);
        } else {
            TableModel model = table.getModel();
            for (int i = 0; i < newData.length; i++) {
                for (int j = 0; j < newData[i].length; j++) {
                    if (newData[i][j].equals(model.getValueAt(i, j)) ||
                        makeBold((String) newData[i][j]).equals(model.getValueAt(i, j))) {
                        model.setValueAt(newData[i][j], i, j);
                    } else {
                        model.setValueAt(makeBold((String) newData[i][j]), i, j);
                    }
                }
            }
        }
        if (rows == 0) {
            this.pack();
        }

        // Resize the window if the number of columns changes and the minimum size may not be enough
        if (newColumns.length != cols && newColumns.length > 2) {
            Dimension size = this.getBounds().getSize();
            size.setSize(INITIAL_WIDTH, size.getHeight());
            this.setSize(size);
        }
        
        cols = newColumns.length;
        rows = newData.length;

        this.revalidate();
        this.repaint();
    }

    public int[] getSelectedRows() {
        return table.getSelectedRows();
    }

    public int getSelectedRowUnsorted(int r) {
        return table.convertRowIndexToModel(r);
    }
    
    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }
    
    public String getValue(int x, int y) {
    	return table.getModel().getValueAt(x,y).toString();
    }

    public void clearSelection() {
        table.clearSelection();
    }

    private String makeBold(String value) {
        return "<html><b>" + value + "</b></html>";
    }
}
