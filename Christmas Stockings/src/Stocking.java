/**
 * This program aims to take stock information and make intelligent decisions on which stocks
 *  seem most profitable at the current runtime.
 *  
 *  Dev Notes: look into Esper for realtime processing.
 */

import java.io.*;
import java.util.*;

import javax.swing.*;
import javax.swing.GroupLayout.SequentialGroup;

import java.awt.*;
import java.awt.event.*;

/**
 * @author Zane Zglobicki
 *
 */
public class Stocking extends JFrame {
		
		private JPanel panel1, panel2, panel3, panel4, panel5, panel6;
		private ButtonGroup radioButtonGroup;
		private final int WINDOW_WIDTH = 1400;
		private final int WINDOW_HEIGHT = 800;
		private JRadioButton infoButton;
		private JRadioButton profitButton;
		private String[] buttonStrings = { "All Stocks", "Dow", "Nasdaq", "S&P 500", "<Stock Area>"};
		private JComboBox stockOptions;
		private JTextField stock;
		private JComboBox areas;
		private JButton start;
		private JTextField date1;
		private JTextField date2;
		
		private JLabel stockLabel;
		private JLabel orFromLabel;
		private JLabel functionLabel;
		private JLabel dateLabel;
		private JLabel date;
		private JLabel to;
		
		String	fileName;
		File	file;
		Scanner	inputFile;
		String	line;
		String	token;
		StringTokenizer	input;
		
		//String[] columnValues = line.split(",");
		
		
	public Stocking()
	{
		setTitle("Stockings");
		setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		
		setLayout(new GridLayout(3, 0));
		
		buildPanel1();
		add(panel1);
		
		
	}
	
	private void buildPanel1()
	{
		panel1 = new JPanel();
		panel1.setBackground(Color.WHITE);
		
		buildPanel2();
		buildPanel3();
		buildPanel4();
		buildPanel5();
		buildPanel6();
		
		
		
		panel1.add(panel2);
		panel1.add(panel3);
		panel1.add(panel4);
		panel1.add(panel6);
		panel1.add(panel5);
		
		/**
		GroupLayout layout = new GroupLayout(left);
		left.setLayout(layout);
		
		layout.setAutoCreateContainerGaps(true);
		layout.setAutoCreateGaps(true);
		
		//SequentialGroup h1 = layout.createSequentialGroup();
		//SequentialGroup h2 = layout.createSequentialGroup();
		
		//h1.addContainerGap();
		//h2.addContainerGap();
		
		//h1.addComponent(stockLabel);
		//h1.addComponent(stock);
		//h2.addComponent(orFromLabel);
		//h2.addComponent(areas);
		
		//.addComponent(stockLabel).addComponent(stock).addComponent(orFromLabel).addComponent(areas)
		
		layout.setHorizontalGroup(
			layout.createSequentialGroup().addGroup(layout.createParallelGroup(GroupLayout.Alignment.LEADING)
					.addComponent(stockLabel).addComponent(stock).addComponent(orFromLabel).addComponent(areas)
					.addComponent(functionLabel).addComponent(infoButton).addComponent(profitButton)
					.addComponent(start))
		);
		layout.setVerticalGroup(
			layout.createSequentialGroup().addComponent(stockLabel).addComponent(stock).addComponent(orFromLabel)
				.addComponent(areas).addComponent(functionLabel).addComponent(infoButton).addComponent(profitButton)
				.addComponent(start).addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE))
		);
		 */
	}
	
	private void buildPanel2()
	{
		panel2 = new JPanel();
		panel2.setBackground(Color.WHITE);
		stockLabel = new JLabel();
		stockLabel.setText("Stock: ");
		
		stock = new JTextField(10);
		
		panel2.setLayout(new FlowLayout(FlowLayout.RIGHT));
		
		panel2.add(stockLabel);
		panel2.add(stock);
	}
	
	private void buildPanel3()
	{
		panel3 = new JPanel();
		panel3.setBackground(Color.WHITE);
		orFromLabel = new JLabel();
		orFromLabel.setText("or From: ");
		
		areas = new JComboBox(buttonStrings);
		areas.setSelectedIndex(4);
		areas.addActionListener(new ComboBoxListener());
		
		panel3.setLayout(new FlowLayout(FlowLayout.RIGHT));
		
		panel3.add(orFromLabel);
		panel3.add(areas);
	}
	
	private void buildPanel4()
	{
		panel4 = new JPanel();
		panel4.setBackground(Color.WHITE);
		functionLabel = new JLabel();
		functionLabel.setText("Function:");
		
		infoButton = new JRadioButton("Info");
		profitButton = new JRadioButton("Profit");
		
		radioButtonGroup = new ButtonGroup();
		radioButtonGroup.add(infoButton);
		radioButtonGroup.add(profitButton);
		
		infoButton.addActionListener(new RadioButtonListener());
		profitButton.addActionListener(new RadioButtonListener());
		
		panel4.setLayout(new FlowLayout(FlowLayout.RIGHT));
		
		panel4.add(functionLabel);
		panel4.add(infoButton);
		panel4.add(profitButton);
	}
	
	private void buildPanel5()
	{
		panel5 = new JPanel();
		panel5.setBackground(Color.WHITE);
		start = new JButton("Start!");
		start.addActionListener(new ButtonListener());
		
		panel5.add(start);
	}
	
	private void buildPanel6()
	{
		panel6 = new JPanel();
		panel6.setBackground(Color.WHITE);
		date = new JLabel("Date: From: ");
		to = new JLabel(" to: ");
		date1 = new JTextField(10);
		date2 = new JTextField(10);
		
		panel6.setLayout(new FlowLayout(FlowLayout.RIGHT));
		
		panel6.add(date);
		panel6.add(date1);
		panel6.add(to);
		panel6.add(date2);
	}
	/**
	 * @param args
	 */
	public static void main(String[] args) 
	{
		Stocking myWindow = new Stocking();
		myWindow.setVisible(true);

	}
	
	//TODO: Create scanner for yahoo csv file, and get csv file, and parse through it.
	private String getStock(String stock)
	{
		
	}
	
	private String getStocks(String area)
	{
		
	}
	
private class ButtonListener implements ActionListener{
		
	public void actionPerformed(ActionEvent e)
	{
	
		if (stock != null)
		{
		//		get the stock symbol, and other info. Ignore or From.	
		}
		
		else if (areas.getSelectedIndex() != 4)
		{
			//scan all stocks in area, and compare them, depending on function.
		}
		
		else 
		{
			//output error message in text field.
		}
		
	}
}


/**
 * These classes may not be needed...
 */
private class RadioButtonListener implements ActionListener{
	
	public void actionPerformed(ActionEvent e)
	{
		
		String actionCommand = e.getActionCommand();
		
	}
}

private class ComboBoxListener implements ActionListener{
	
	public void actionPerformed(ActionEvent e)
	{
		
		String actionCommand = e.getActionCommand();
		
	}
}

}
