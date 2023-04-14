package com.equensworldline.amq.utils.gui;

import javax.swing.*;
import javax.swing.table.TableModel;
import java.awt.*;

public final class ShowTable extends JFrame {

    //empty headers for the table
    private static String[] columns = new String[]{};

    //empty data for the table in a 2d array
    private static Object[][] data = new Object[][]{{}};

    private static int initialXLoc = 0;
    private static int initialYLoc = 0;
    private static final int INITIAL_WIDTH = 1024;
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
        this.setLocation(initialXLoc, initialYLoc);
        shiftInitialLocationForNewWindow();
        cols = columns.length;
        rows = data.length;
    }

    /**
     * Shifts the initial location down and right with 25 pixels to get a stack of windows all visible when starting for
     * multiple brokers. Will stack up to 8 windows before restarting at location (0,0)
     */
    private static void shiftInitialLocationForNewWindow() {
        initialXLoc = (initialXLoc + 25) % 200;
        initialYLoc = (initialYLoc + 25) % 200;
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

        // Resize the window if the number of columns changes and the minimum size may not be enough
        if (newColumns.length > 2) {
        	Dimension size = this.getBounds().getSize();
        	if (newColumns.length != cols && size.getWidth()<INITIAL_WIDTH) {
        		size.setSize(INITIAL_WIDTH, size.getHeight());
        		this.setSize(size);
        	}
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
        return table.getModel().getValueAt(x, y).toString();
    }

    public void clearSelection() {
        table.clearSelection();
    }

    private String makeBold(String value) {
        return "<html><b>" + value + "</b></html>";
    }

    public void setMenu(JMenuBar menuBar) {
        this.add(menuBar, BorderLayout.NORTH);
    }
}
