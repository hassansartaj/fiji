	/**
	 * This plugin is a merge of the Time_Stamper plugins from ImageJ and from Tony Collins' plugin collection at macbiophotonics. 
	 *it aims to combine all the functionality of both plugins and refine and enhance their functionality
	 *for instance by adding the preview functionality suggested by Michael Weber.
	 *
	 *It does not know about hyper stacks - multiple channels..... only works as expected for normal stacks.
	 *That means a single channel time series or z stack. 
	 *
	 *We might want to rename this tool "Stack Labeler", since it will handle labeling of Z stacks as well as time stacks. 
	 *
	 *The sequence of calls to an ExtendedPlugInFilter is the following:
	 *- setup(arg, imp): The filter should return its flags.
	 *- showDialog(imp, command, pfr): The filter should display the dialog asking for parameters (if any)
	 *and do all operations needed to prepare for processing the individual image(s) (E.g., slices of a stack).
	 *For preview, a separate thread may call setNPasses(nPasses) and run(ip) while the dialog is displayed.
	 *The filter should return its flags.
	 *- setNPasses(nPasses): Informs the filter of the number of calls of run(ip) that will follow.
	 *- run(ip): Processing of the image(s). With the CONVERT_TO_FLOAT flag,
	 *this method will be called for each color channel of an RGB image.
	 *With DOES_STACKS, it will be called for each slice of a stack.
	 *- setup("final", imp): called only if flag FINAL_PROCESSING has been specified.
	 *Flag DONE stops this sequence of calls.
	 *
	 *We are using javas calendar for formatting the time stamps for "digital" style, 
	 *but this has limitations, as you can t count over 59 min and the zero date is 01 01 1970, not zero. 
	 *
	 *Here is a list (in no particular order) of requested and "would be nice" features that could be added:
	 *-prevent longest label running off side of image  - ok
	 *-choose colour  -ok
	 *-font selection -ok
	 *-top left, bottom right etc.  drop down menu 
	 *-Hyperstacks z, t, c
	 *-read correct time / z units, start and intervals from image metadata. Get it from Image Properties?
	 *-every nth slice labelled -ok
	 *-label only slices where time became greater than multiples of some time eg every 5 min. 
	 *-preview with live update when change GUI -ok, changes in GUI are read into the preview. 
	 *-preview with stack slider in the GUI. - slider now in GUI but functionality is half broken
	 *-Use Java Date for robust formatting of dates/times counted in milliseconds. - added hybrid date form at for 
	 *-versatile formatting of the digital time - ok 
	 *-switch unit according to magnitude of number eg sec or min or nm or microns etc. 
	 *- background colour for label. -0K.  
	 *
	 *Dan White MPI-CBG , began hacking on 15.04.09. Work continued from 02-2010 by Tomka and Dan
	 */

import ij.IJ;
import ij.IJEventListener;
import ij.ImagePlus;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Roi;
import ij.gui.TextRoi;
import ij.gui.Toolbar;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.plugin.frame.ColorPicker;
import ij.plugin.frame.Fonts;
import ij.process.ImageProcessor;

import java.awt.AWTEvent;
import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.Panel;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.TextEvent;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class Time_Stamper_Enhanced implements ExtendedPlugInFilter,
		DialogListener, FocusListener, DocumentListener {

	ImagePlus imp;
	// the px distance to the left
	int x = 2;
	// the px distance to the top
	int y = 15;
	// the font to draw the text with
	Font font;
	double start = 1.0;
	double interval = 1.0;
	double lastTime;
	// the custom suffix, used if format supports it
	String customSuffix = "";
	// the suffix to append to the time stamp, used if
	// format supports supports no custom suffix
	static String chosenSuffix = "s";
	// the amount of decimal places, used by some formats
	int decimalPlaces = 3;
	// indicates if processing has been canceled or not
	boolean canceled;
	// indicates if we are in preview mode or do the actual processing
	boolean preview = true;
	// the custom pattern for labeling, used if format supports it
	String customFormat = "";
	// indicates if the text should be anti-aliased or not
	boolean antiAliasedText = true;
	// the current frame 
	int frame;
	// a visibility range for the stamps, these default to 0 as no values are given
	int first, last;
	// the 'n' for 'label every n-th frame'. Treated as 1 for values below one
	int frameMask = 1;
	// the object that runs the plugin.
	PlugInFilterRunner pluginFilterRunner; 
	// a combination (bitwise OR) of the flags specified in interfaces PlugInFilter 
	// and ExtendedPlugInFilter. Determines what kind of image the plug-in can run on etc.
	int flags = DOES_ALL + DOES_STACKS + STACK_REQUIRED;

	// the currently selected format
	AbstractStampFormat selectedFormat;
	// background of timestamp/label enabled
	private boolean backgroundEnabled = false;

	// member variable for the GUI dialog
	private GenericDialog gd;
	
	// String representations of the location presets offered
	static final String[] locations = {"Upper Right", "Lower Right", "Lower Left", "Upper Left", "Custom"};
	// Some index aliases for the locations array
	static final int UPPER_RIGHT=0, LOWER_RIGHT=1, LOWER_LEFT=2, UPPER_LEFT=3, CUSTOM=4;
	// the currently selected location preset
	int locationPreset;
	
	// the available kinds of stack we can label. 
	final String[] stackTypes = { "z-stack", "time series / movie" };
	
	// the available time formats
	final AbstractStampFormat[] formats = {new DecimalLabelFormat(), new DigitalLabelFormat(), new CustomLabelFormat()};
	
	// GUI variables that are needed to read out data
	// from the components
	private JComboBox labelFormatsComboBox;
	private JComboBox labelUnitsComboBox;
	private JComboBox stackTypeComboBox;
	private JComboBox locationPresetsComboBox;
	private JPanel generalSettingsContainer;
	private JPanel unitsFormattingContainer;
	private JPanel startStopIntervalsContainer;
	private JPanel locationFontContainer;
	private JPanel fontPropertiesContainer;
	private JTextField customLabelFormatTextField;
	private JTextField customSuffixTextField;
	private JTextField decimalPlacesTextField;
	private JTextField startupTextField;
	private JTextField intervalTextField;
	private JTextField everyNthTextField;
	private JTextField firstFrameTextField;
	private JTextField lastFrameTextField;
	private JTextField locationXTextField;
	private JTextField locationYTextField;
	
	// the panel containing the units selection
	private JPanel labelUnitsPanel;
	// the panel containing the custom suffix elements
	private JPanel customSuffixPanel;
	// the panel containing the custom formats elements
	private JPanel customLabelFormatPanel;
	// the panel containing the Decimal Places elements
	private JPanel decimalPlacesPanel;

	/**
	 * Setup the plug-in and tell ImageJ it needs to work on a stack by
	 * returning the flags
	 */
	public int setup(String arg, ImagePlus imp) {
		this.imp = imp;
		IJ.register(Time_Stamper_Enhanced.class);
		if (imp != null) {
			first = 1;
			last = imp.getStackSize();
			setFontParams();
		}

		// set the currently selected format to the first available
		selectedFormat = formats[0];

		// return supported flags
		return flags;
	}

	/**
	 * Make the GUI for the plug-in, with fields to fill all the variables we
	 * need. we are using ExtendedPluginFilter, so first argument is imp not ip.
	 */
	public int showDialog(ImagePlus imp, String command, PlugInFilterRunner pfr) {
		this.pluginFilterRunner = pfr;
		int subpanelHeight = 30;
		int left = 20;
		
		// This makes the GUI object
		gd = new NonBlockingGenericDialog(
				"Time Stamper Enhanced");
		
		//
		// General settings panel
		//
		generalSettingsContainer = createContainerPanel(70, "General Settings");
		
		//add combobox for stack type
		stackTypeComboBox = new JComboBox();
		JPanel stackTypePanel = createComboBoxPanel("Stack Type", stackTypeComboBox, stackTypes, 1, 100, 180);
		stackTypePanel.setLocation(left, 30);
		
		addPanelsToDialg(generalSettingsContainer,
				new JPanel[] {stackTypePanel} );
		
		//
		// Units formatting panel
		//
		unitsFormattingContainer = createContainerPanel(100, "Units Formatting");
		
		// add combobox for label format
		labelFormatsComboBox = new JComboBox();
		JPanel pLabelFormat = createComboBoxPanel("Label Format", labelFormatsComboBox, getAvailableFormats(), 0);
		pLabelFormat.setLocation(left, 30);
        
		// add combobox for label unit
		labelUnitsComboBox = new JComboBox();
		labelUnitsPanel = createComboBoxPanel("Label Unit", labelUnitsComboBox, selectedFormat.getAllowedFormatUnits(), 0);
		labelUnitsPanel.setLocation(left, 60);
        
        // add Custom Suffix panel
		customSuffixTextField = new JTextField();
		customSuffixPanel = createTextFieldPanel("Custom Suffix", customSuffixTextField, customSuffix);
		customSuffixPanel.setLocation(left, 60);
		
		// add Decimal Places panel
		decimalPlacesTextField = new JTextField();
		decimalPlacesPanel = createTextFieldPanel("Decimal Places", decimalPlacesTextField, Integer.toString(decimalPlaces));
		decimalPlacesPanel.setLocation(300, 30);
		
		// add Custom Format panel
		customLabelFormatTextField = new JTextField();
		customLabelFormatPanel = createTextFieldPanel("Custom Format", customLabelFormatTextField, customFormat);
		customLabelFormatPanel.setLocation(300, 30);

		addPanelsToDialg(unitsFormattingContainer,
				new JPanel[] {pLabelFormat, customSuffixPanel, labelUnitsPanel, decimalPlacesPanel, customLabelFormatPanel} );
		
		//
		// Start/Stop/Interval
		//
		startStopIntervalsContainer = createContainerPanel(130, "Start/Stop/Interval of Stack");
		
		// add a panel for the time stamper start value
		startupTextField = new JTextField();
		JPanel pStartup = createTextFieldPanel("Startup", startupTextField, IJ.d2s(start));
		pStartup.setLocation(left, 30);
		
		// add a panel for the interval settings
		intervalTextField = new JTextField();
		JPanel pInterval = createTextFieldPanel("Interval", intervalTextField, IJ.d2s(interval));
		pInterval.setLocation(left, 60);
		
		// add panel for the everyNth setting
		everyNthTextField = new JTextField();
		JPanel pEveryNth = createTextFieldPanel("Every n-th", everyNthTextField, Integer.toString(frameMask));
		pEveryNth.setLocation(left, 90);
		
		// add panel for First Frame setting
		firstFrameTextField = new JTextField();
		JPanel pFirstFrame = createTextFieldPanel("First", firstFrameTextField, Integer.toString(first));
		pFirstFrame.setLocation(300, 30);
		
		// add panel for Last Frame setting
		lastFrameTextField = new JTextField();
		JPanel pLastFrame = createTextFieldPanel("Last", lastFrameTextField, Integer.toString(last));
		pLastFrame.setLocation(300, 60);
		
		addPanelsToDialg(startStopIntervalsContainer,
				new JPanel[] {pStartup, pInterval, pEveryNth, pFirstFrame, pLastFrame} );
		
		//
		// Location and Font panel
		//
		locationFontContainer = createContainerPanel(110, "Location & Font");
		
		// add panel for X location
		locationXTextField = new JTextField();
		JPanel pLocationX = createTextFieldPanel("X", locationXTextField, Integer.toString(x), 20, 50);
		pLocationX.setLocation(left, 30);
		
		// add panel for Y location
		locationYTextField = new JTextField();
		JPanel pLocationY = createTextFieldPanel("Y", locationYTextField, Integer.toString(y), 20, 50);
		pLocationY.setLocation(120, 30);
		
		if (isCustomROI())
			 locationPreset = CUSTOM;
		else locationPreset = UPPER_LEFT;
		
		// add combobox for location presets
		locationPresetsComboBox = new JComboBox();
		JPanel pLocationPresets = createComboBoxPanel("Location Presets",
				locationPresetsComboBox, locations, locationPreset, 110, 150);
		pLocationPresets.setLocation(240, 30);
        
        fontPropertiesContainer = new FontPropertiesPanel();
  		fontPropertiesContainer.setBounds(left, 70, 400, subpanelHeight);
  		
  		addPanelsToDialg(locationFontContainer,
  				new JPanel[] {pLocationX, pLocationY, pLocationPresets, fontPropertiesContainer} );

		gd.addPreviewCheckbox(pfr); // adds preview checkbox - needs
		// ExtendedPluginFilter and DialogListener!
		gd.addMessage("Time Stamper plugin for Fiji (is just ImageJ - batteries included)\n" +
				"maintained by Dan White MPI-CBG dan(at)chalkie.org.uk");

		gd.addDialogListener(this); // needed for listening to dialog
		// field/button/checkbox changes?

		// add an ImageJ event listener to react to color changes, etc.
		IJ.addEventListener(new IJEventListener() {
			public void eventOccurred(int event) {
				if (event == IJEventListener.FOREGROUND_COLOR_CHANGED
						|| event == IJEventListener.BACKGROUND_COLOR_CHANGED) {
					updatePreview(null);
				}
			}
		});
		
		//add a help button that opens a link to the documentation wiki page. 
		gd.addHelp("http://pacific.mpi-cbg.de/wiki/index.php/Stack_labeler");

		// update GUI parts that are dependent on current variable contents
		updateUI();

		gd.showDialog(); // shows the dialog GUI!

		// handle the plug-in cancel button being pressed.
		if (gd.wasCanceled())
			return DONE;

		// if the ok button was pressed, we are really running the plug-in,
		// so later we can tell what time stamp to make as its not the last
		// as used by preview
		preview = !gd.wasOKed();

		// extendedpluginfilter showDialog method should
		// return a combination (bitwise OR) of the flags specified in
		// interfaces PlugInFilter and ExtendedPlugInFilter.
		return flags;
	}

	/**
	 * This is part of the preview functionality. Informs the filter of the
	 * number of calls of run(ip) that will follow. The argument nPasses is
	 * worked out by the plug-in runner.
	 */
	public void setNPasses(int nPasses) {
		// frame = first; // dont need this
		// time = lastTime(); // set the time to lastTime, when doing the
		// preview run,
		// so the preview does not increment time when clicking the preview box
		// causes run method execution.
		// and i see the longest time stamp that will be made when i do a
		// preview, so i can make sure its where
		// i wanted it.
		// that works, but now the time stamper counts up from time = lastTime
		// value not from time = (start + (first*interval))
		// when making the time stamps for the whole stack...

		if (preview) {
			frame = last;
		} else {
			frame = 1; // so the value of frame is reset to 0 each time the
			// plugin is run or the preview checkbox is checked.
		}
		frame--;
	}

	/**
	 * Run the plug-in on the ip object, which is the ImageProcessor object
	 * associated with the open/selected image. but remember that showDialog
	 * method is run before this in ExtendedPluginFilter
	 */
	public void run(ImageProcessor ip) {
		// this increments frame integer by 1. If an int is declared with no
		// value, it defaults to 0
		frame++;

		// Updates this image from the pixel data in its associated
		// ImageProcessor object and then displays it
		// if it is the last frame. Why do we need this when there is
		// ip.drawString(timeString); below?
		if (frame == last)
			imp.updateAndDraw();

		// the following line isnt needed in ExtendedPluginFilter because
		// setNPasses takes care of number of frames to write in
		// and ExtendedPluginFilter executes the showDialog method before the
		// run method, always, so don't need to call it in run.
		// if (frame==1) showDialog(imp, "TimeStamperEnhanced", pfr); // if at
		// the 1st frame of the stack, show the GUI by calling the showDialog
		// method
		// and set the variables according to the GUI input.

		// tell the run method when to not do anything just return
		// Here there is a bug: with the new use of ExtendedPluginFilter,
		// using preview on, the first time stamp is placed in frame first-1 not
		// first...
		// and the last time stamp in last-1. With preview off it works as
		// expected.
		if ((!preview) && (canceled || frame < first || frame > last || (frame % frameMask != 0)))
			return;

		// if (fontProperties != null)
		// fontProperties.updateGUI(font);
		ip.setFont(font);
		ip.setAntialiasedText(antiAliasedText);

		// Have moved the font size and xy location calculations for timestamp
		// stuff out of the run method, into their own methods.
		// set the font size according to ROI size, or if no ROI the GUI text
		// input
		Rectangle backgroundRectangle = getBoundingRectangle(ip);
		if (backgroundEnabled){
			ip.setColor(Toolbar.getBackgroundColor());
			ip.fill(new Roi(backgroundRectangle));
		}
		ip.setColor(Toolbar.getForegroundColor());
		ip.moveTo(backgroundRectangle.x, (backgroundRectangle.y + backgroundRectangle.height) );

		double time = getTimeFromFrame(frame); // ask the getTimeFromFrame
		// method to return the time for
		// that frame
		ip.drawString(selectedFormat.getTimeString(time)); // draw the
		// timestring into
		// the image
		// showProgress(percent done calc here); // dont really need a progress
		// bar... but seem to get one anyway...
	}
	
	private JPanel createContainerPanel(int height, String label){
		JPanel panel = new JPanel(null);
		panel.setPreferredSize(new Dimension(540, height));
		panel.setBorder(javax.swing.BorderFactory.createTitledBorder(label));
		return panel;
	}
	
	private JPanel createComboBoxPanel(String labelText, JComboBox combobox, String[] values, int defaultIndex) {
		return createComboBoxPanel(labelText, combobox, values, defaultIndex, 100, 150);
	}
	
	private JPanel createComboBoxPanel(String labelText, JComboBox combobox, String[] values, int defaultIndex, int labelWidth, int comboboxWidth) {
		JLabel label = new JLabel(labelText);
		combobox.setModel(new javax.swing.DefaultComboBoxModel(values));
        registerComboBox(combobox, defaultIndex);
        JPanel panel = new JPanel(null);
        label.setBounds(0, 0, labelWidth, 20);
        combobox.setBounds(labelWidth, 0, comboboxWidth, 22);
        panel.add(label);
        panel.add(combobox);
        panel.setSize(labelWidth+comboboxWidth, 30);
        return panel;
	}
	
	private JPanel createTextFieldPanel(String labelText, JTextField textfield, String defaultText) {
		return createTextFieldPanel(labelText, textfield, defaultText, 100, 100);
	}
	
	private JPanel createTextFieldPanel(String labelText, JTextField textfield, String defaultText, int labelWidth, int textFieldWidth) {
		JLabel label = new JLabel(labelText);
		registerTextField(textfield, defaultText);
		JPanel panel = new JPanel(null);
		label.setBounds(0,0, labelWidth, 20);
		textfield.setBounds(labelWidth,0,textFieldWidth, 22);
		panel.add(label);
		panel.add(textfield);
		panel.setSize(labelWidth+textFieldWidth, 30);
		return panel;
	}
	
	private void addPanelsToDialg(JPanel container, JPanel[] panels) {
		for (JPanel p : panels) {
			container.add(p);
		}
		
		Panel awtPanel = new Panel();
		awtPanel.add(container);
		gd.addPanel(awtPanel, GridBagConstraints.CENTER, new Insets(5, 0, 0, 0));
	}

	private void registerTextField(JTextField tf, String defaultText) {
		tf.setText(defaultText);
		tf.addActionListener(gd);
		tf.addKeyListener(gd);
		tf.addFocusListener(this);
		tf.getDocument().addDocumentListener(this);
	}
	
	private void registerComboBox(JComboBox cb, int defaultSelection) {
		cb.setSelectedIndex(defaultSelection);
		cb.addItemListener(gd);
		cb.addKeyListener(gd);
	}
	
	private void registerCheckBox(JCheckBox cb) {
		cb.addItemListener(gd);
		cb.addKeyListener(gd);
	}
	
	public void focusGained(FocusEvent e) {
		Component c = e.getComponent();
		if (c instanceof JTextField)
			((JTextField)c).selectAll();
	}

	public void focusLost(FocusEvent e) {
		Component c = e.getComponent();
		if (c instanceof JTextField)
			((JTextField)c).select(0,0);
	}
	
	public void changedUpdate(DocumentEvent e) {
		announceTextChange(e);
	}

	public void insertUpdate(DocumentEvent e) {
		announceTextChange(e);
	}

	public void removeUpdate(DocumentEvent e) {
		announceTextChange(e);
	}
	
	private void announceTextChange(DocumentEvent e) {
		TextEvent te = new TextEvent(e.getDocument(), e.getOffset());
		gd.textValueChanged(te);
	}

	/**
	 * Updates the preview.
	 * 
	 * @param dialog
	 * @param e
	 */
	private void updatePreview(AWTEvent e) {
		if (gd != null){
			// tell the plug-in filter runner to update
			// the preview. Apparently, this is "OK"
			pluginFilterRunner.dialogItemChanged(gd, e);
		}
	}
	
	/**
	 * Updates some GUI components, based on the current state of the selected
	 * label format.
	 */
	private void updateUI() {
		// if the new format supports custom suffixes, enable
		// the custom suffix panel and deactivate unit selection
		boolean supportsCustomSuffix = selectedFormat.supportsCustomSuffix();
		customSuffixPanel.setVisible(supportsCustomSuffix);
		labelUnitsPanel.setVisible(!supportsCustomSuffix);
		// if the current format supports custom format, enable
		// the custom format panel
		customLabelFormatPanel.setVisible(selectedFormat.supportsCustomFormat());
		// if the current format supports decimal places, enable
		// the decimal places panel
		decimalPlacesPanel.setVisible(selectedFormat.supportsDecimalPlaces());
	}
	
	/**
	 * This method to deals with changes in the GUI Should move this after the run
	 * method to keep code style method order: setup, showDialog, setNPasses,
	 * run, other methods.
	 */
	public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
		// has the label format been changed?
		int currentFormat = labelFormatsComboBox.getSelectedIndex();
		AbstractStampFormat lf = formats[currentFormat];
		if (lf != selectedFormat) {
			selectedFormat = lf;
			// if the format has changed, we need to modify the
			// units choice accordingly
			labelUnitsComboBox.removeAllItems();
			for (String unit : selectedFormat.getAllowedFormatUnits()) {
				labelUnitsComboBox.addItem(unit);
			}
		}

		customFormat = customLabelFormatTextField.getText();

		// get the selected suffix of drop-down list
		chosenSuffix = (String) labelUnitsComboBox.getSelectedItem();

		customSuffix = customSuffixTextField.getText();
		decimalPlaces = Integer.parseInt(decimalPlacesTextField.getText());
		start = Double.parseDouble(startupTextField.getText());
		interval = Double.parseDouble(intervalTextField.getText());
		// has a different location preset has been selected?
		int preset = locationPresetsComboBox.getSelectedIndex();
		if (preset != locationPreset){
			locationPreset = preset;
			Point p = getPresetPosition(preset);

			locationXTextField.setText(Integer.toString(p.x));
			locationYTextField.setText(Integer.toString(p.y));
		}
		
		try {
			int curX = Integer.parseInt(locationXTextField.getText());
			if (curX != x) {
				x = curX;
				locationPresetsComboBox.setSelectedIndex(CUSTOM);
			}
		}
		catch (NumberFormatException ex) { return false; }
		
		try {
			int curY = Integer.parseInt(locationYTextField.getText());
			if (curY != y) {
				y = curY;
				locationPresetsComboBox.setSelectedIndex(CUSTOM);
			}
		}
		catch (NumberFormatException ex) { return false; }
		
		
		first = Integer.parseInt(firstFrameTextField.getText());
		last = Integer.parseInt(lastFrameTextField.getText());
		frameMask = Integer.parseInt(everyNthTextField.getText());

		updateUI();

		return true; // or else the dialog will have the ok button deactivated!
	}
	
	/**
	 * Creates a string array out of the names of the available formats.
	 */
	private String[] getAvailableFormats() {
		String[] formatArray = new String[formats.length];
		int i = 0;
		for (AbstractStampFormat t : formats) {
			formatArray[i] = t.getName();
			i++;
		}
		return formatArray;
	}
	
	/**
	 * Returns true if a custom ROI has been selected, i.e if the current
	 * ROI has not the extent of the whole image.
	 * @return true if custom ROI selected, false otherwise
	 */
	boolean isCustomROI(){
		Roi roi = imp.getRoi();
		if (roi == null)
			return false;
		
		Rectangle theROI = roi.getBounds();

		return (theROI.height != imp.getHeight()
					|| theROI.width != imp.getWidth()); // if the ROI is the same
	}

	/**
	 * Works out the size of the font to use.
	 */
	void setFontParams() {
		// from the size of the ROI box drawn,
		// if one was drawn (how does it know?)
		int size = 12;
		
		if (isCustomROI()){
			Roi roi = imp.getRoi();
			Rectangle theROI = roi.getBounds();
			
			/*
			 * single characters fit the ROI, but if the time stamper string is
			 * long then the font is too big to fit the whole thing in!
	         *
			 * need to see if we should use the ROI height to set the font size
			 * or read it from the plugin gui if (theROI != null) doesnt work as
			 * if there is no ROI set, the ROI is the size of the image! There
			 * is always an ROI! So we can say, if there is no ROI set , its
			 * the same size as the image, and if that is the case, we should
			 * then use the size as read from the GUI.
			 * remember the ROIs top left coordinates to be the X and Y values
			 * of the font rectangle
			 */
			x = theROI.x;
			y = theROI.y;

			/*
			 * size as the image, leave size as it was set by the gui.
			 * For now, ignore point ti pixel conversion.
			 */
			size = (int) (theROI.height);

			/*
			 * make sure the font is not too big or small.... but why? Too -
			 * small cant read it. Too Big - ?
			 * should this use private and public and get / set methods?
			 * in any case it doesnt seem to work... i can set the font < 7 and
			 * it is printed that small.
			 */
			if (size < 7)
				size = 7;
			else if (size > 80)
				size = 80;
		}
		// if no ROI, x and y are defaulted or set according to text in gui

		font = new Font(TextRoi.getFont(), TextRoi.getStyle(), size);
	}

	/**
	 * Gets a valid bounding rectangle for the time stamp positioning.
	 * It aims for having all of the time stamp on the	image, even for
	 * the last frames with bigger numbers.
	 * It tries to move the time stamp right a bit to account for the
	 * max length the time stamp will be.
	 */
	Rectangle getBoundingRectangle(ImageProcessor ip) {
		Rectangle roi = ip.getRoi();
		// set the xy time stamp drawing position for ROI smaller than the
		// image, to top left of ROI and make its height be the font size
		if (roi.width == imp.getWidth() && roi.height == imp.getHeight()) {
			roi.x = x;
			roi.y = y;
			roi.height = font.getSize();
		}
			
		// make sure the y position is not less than the font height: size,
		// so the time stamp is not off the top of the image?
		if ( (roi.y + roi.height) < font.getSize()) {
			y = roi.y = 1;
		} 
		
		// make sure the stamp falls not off the bottom
		if (ip.getHeight() < (roi.y + font.getSize())) {
			y = roi.y = ip.getHeight() - font.getSize();
		}
		
		// assume that the last time stamp is the widest one to calculate
		// the maximum width of the bounding rectangle. This is not always
		// true (e.g. 0:00 vs. 0:11) and could be made more precise.
		roi.width = ip.getStringWidth(selectedFormat.lastTimeStampString());
		
		// if longest time stamp is wider than (image width - ROI width),
		// move x in appropriately
		if (roi.width > (ip.getWidth() - roi.x)) {
			x = roi.x = (ip.getWidth() - roi.width);
		}
		
		return roi;
	}
	
	Point getPresetPosition(int preset) {
		ImageProcessor ip = imp.getProcessor();
		if (preset == UPPER_LEFT){
			x = 0;
			y = 0;
		}
		else if (preset == UPPER_RIGHT){
			x = ip.getWidth();
			y = 0;
		}
		else if (preset == LOWER_LEFT){
			x = 0;
			y = ip.getHeight();
		}
		else if (preset == LOWER_RIGHT){
			x = ip.getWidth();
			y = ip.getHeight();
		}
		else return new Point(x, y);
		
		Rectangle rect = getBoundingRectangle(ip);
		
		return new Point(rect.x, rect.y);
	}

	/**
	 * this method adds a preceding 0 to a number if it only has one digit
	 * instead of two. Which is handy for making 00:00 type format strings
	 * later. Thx Dscho.
	 */
	String twoDigits(int value) {
		return (value < 10 ? "0" : "") + value;
	}

	/**
	 * Returns the time of the last frame where a stamp is made.
	 */
	double lastTime() {
		return getTimeFromFrame(last); 
	}

	/**
	 * Returns the time of a specific frame.
	 * 
	 * @param f The frame to calculate the time of
	 * @return The time at frame f
	 */
	double getTimeFromFrame(int f) {
		return start + (interval * (f - 1)); // is the time for a certain frame
	}

	/**
	 * A class representing a supported label format of the time stamper
	 * plug-in. It relates supported format units/suffixes to a format name.
	 * Besides that it determines if a custom suffix should be usable.
	 */
	protected abstract class AbstractStampFormat {
		// an array of all the supported units for this format
		String[] allowedFormatUnits;
		// the display name for this format
		String name;
		// a member indicating if a custom suffix should be supported
		boolean customSuffixSupported;
		// a member indicating if a custom format should be supported
		boolean customFormatSupported;
		// a member indicating if decimal places modification is supported
		boolean decimalPlacesSupported;
		
		/**
		 * 
		 * @param allowedFormatUnits
		 * @param name
		 * @param supportCustomSuffix
		 * @param supportCustomFormat
		 */
		public AbstractStampFormat(String[] allowedFormatUnits, String name,
				boolean supportCustomSuffix, boolean supportCustomFormat,
				boolean supportDecimalPlaces) {
			this.allowedFormatUnits = allowedFormatUnits;
			this.name = name;
			this.customSuffixSupported = supportCustomSuffix;
			this.customFormatSupported = supportCustomFormat;
			this.decimalPlacesSupported = supportDecimalPlaces;
		}

		/**
		 * Here we make the strings to print into the images. decide if the time
		 * format is digital or decimal according to the plugin GUI input if it
		 * is decimal (not digital) then need to set suffix from drop down list
		 * which might be custom suffix if one is entered and selected. if it is
		 * digital, then there is no suffix as format is set hh:mm:ss.ms
		 * 
		 * @param time
		 * @return
		 */
		public abstract String getTimeString(double time);

		/**
		 * this method returns the string of the TimeStamp for the last frame to
		 * be stamped which is for the frame with value of the last variable. It
		 * should be the longest string the timestamp will be. we should use
		 * this in maxWidth method and for the preview of the timestamp used to
		 * be: maxWidth = ip.getStringWidth(decimalString(start +
		 * interval*imp.getStackSize())); but should use last not stacksize,
		 * since no time stamp is made for slices after last? It also needs to
		 * calculate maxWidth for both digital and decimal time formats:
		 * 
		 * @return
		 */
		public String lastTimeStampString() {
			return getTimeString(lastTime());
		}

		/**
		 * Gets the suffix (if any) that should be appended to the time stamp.
		 * 
		 * @return The suffix to display.
		 */
		public String suffix() {
			return chosenSuffix;
		}

		/**
		 * Gets an array of allowed units for the format. E. g. to display them
		 * in a drop down component.
		 * 
		 * @return The allowed units.
		 */
		public String[] getAllowedFormatUnits() {
			return allowedFormatUnits;
		}

		/**
		 * Gets the display name of the format.
		 * 
		 * @return The display name of the format
		 */
		public String getName() {
			return name;
		}

		/**
		 * Indicates whether custom (user input) suffixes are allowed to this
		 * format or not.
		 * 
		 * @return True if custom suffixes are allowed, false otherwise
		 */
		public boolean supportsCustomSuffix() {
			return customSuffixSupported;
		}

		/**
		 * Indicates whether a custom time format can be used by the format.
		 * This could be useful for digital formats like HH:mm which should be
		 * user definable.
		 * 
		 * @return True if the format supports custom formats, false otherwise.
		 */
		public boolean supportsCustomFormat() {
			return customFormatSupported;
		}
		
		/**
		 * Indicates whether decimal places are part of the format.
		 * This could be useful for decimal formats for which the number of
		 * decimal places should be user definable.
		 * 
		 * @return True if the format supports decimal places, false otherwise.
		 */
		public boolean supportsDecimalPlaces() {
			return decimalPlacesSupported;
		}
	}

	/**
	 * Represents a decimal label format, e. g. 7.3 days.
	 */
	protected class DecimalLabelFormat extends AbstractStampFormat {

		/**
		 * Constructs a new {@link DecimalLabelFormat}. This default constructor
		 * allows years, weeks, days, hours, minutes, seconds and milli-,
		 * micro-, nano-, pico- pico-, femto and attoseconds as formats. The
		 * display name is set to "Decimal".
		 */
		public DecimalLabelFormat() {
			this(new String[] { "y", "w", "d", "h", "min", "s", "ms", "us",
					"ns", "ps", "fs", "as" }, "Decimal");
		}

		/**
		 * Constructs a new {@link DecimalLabelFormat}. The allowed units and a
		 * name are requested as parameters. No custom time format input is
		 * supported, but one can use a custom suffix with this class.
		 * 
		 * @param allowedFormatUnits
		 *            The allowed units for this format.
		 * @param name
		 *            The display name of the format.
		 */
		public DecimalLabelFormat(String[] allowedFormatUnits, String name) {
			this(allowedFormatUnits, name, false, false);
		}

		protected DecimalLabelFormat(String[] allowedFormatUnits, String name,
				boolean supportCustomSuffix, boolean supportCustomFormat) {
			super(allowedFormatUnits, name, supportCustomSuffix,
					supportCustomFormat, true);
		}

		/**
		 * Makes the string containing the number for the time stamp, with
		 * specified decimal places format is decimal number with specified no
		 * of digits after the point if specified no. of decimal places is 0
		 * then just return the specified suffix
		 */
		@Override
		public String getTimeString(double time) {
			if (interval == 0.0)
				return suffix();
			else
				return (decimalPlaces == 0 ? "" + (int) time : IJ.d2s(time,
						decimalPlaces))
						+ " " + suffix();
		}
	}

	/**
	 * Represents a digital label format, e. g. HH:mm:ss:ms.
	 */
	protected class DigitalLabelFormat extends AbstractStampFormat {
		// A calendar to calculate time representation with.
		TimeZone tz = TimeZone.getTimeZone("UTC");
		Calendar calendar = new GregorianCalendar();

		/**
		 * Constructs a new {@link DigitalLabelFormat}. This default constructor
		 * allows minutes, seconds and milliseconds only as possible formats.
		 * The display name is set to "Digital".
		 */
		public DigitalLabelFormat() {
			this(new String[] { "min", "s", "ms" }, "Digital");
		}

		/**
		 * Constructs a new {@link DigitalLabelFormat}. The allowed units and a
		 * name are requested as parameters. No custom time format input is
		 * supported, but one can use a custom suffix with this class.
		 * 
		 * @param allowedFormatUnits
		 *            The allowed units for this format.
		 * @param name
		 *            The display name of the format.
		 */
		public DigitalLabelFormat(String[] allowedFormatUnits, String name) {
			super(allowedFormatUnits, name, false, true, false);
		}

		/**
		 * Makes the string containing the number for the time stamp, with
		 * hh:mm:ss.decimalPlaces format which is nice, but also really need
		 * hh:mm:ss and mm:ss.ms etc. could use the java time/date formating
		 * stuff for that?
		 */
		@Override
		public String getTimeString(double time) {
			calendar.setTimeInMillis(0);
			if (chosenSuffix.equals("min")) {
				time = time * (60.0 * 1000.0);
			} else if (chosenSuffix.equals("s")) {
				time = time * 1000.0;
			} else if (chosenSuffix.equals("ms")) {
				// trivial case, nothing to do:
				// time = time;
			}
			else {
				IJ.error("For a digital 00:00:00.000 time you must use min, s or ms only as the time units.");
			}

			// set the time
			calendar.setTimeInMillis(Math.round(time));
			DateFormat f;
			try {
				// check if a custom format has been entered
				if (customFormat.length() > 0) {
					// Make sure that our custom format can be handled
					f = new HybridDateFormat(customFormat);
				} else {
					f = new SimpleDateFormat("HH:mm:ss.SSS");
				}
				f.setTimeZone(tz); // the SimpleDateFormat needs to know the
				// time zone is UTC!

				return f.format(calendar.getTime());
			} catch (IllegalArgumentException ex) {
				return ex.getMessage();
			}
		}
	}

	/**
	 * Represents a label format that is essentially the same as a decimal
	 * format, but allows custom suffixes.
	 */
	protected class CustomLabelFormat extends DecimalLabelFormat {

		/**
		 * Creates a new {@link CustomLabelFormat}. It allows only custom
		 * suffixes as units and its display name is set to "Custom Format".
		 */
		public CustomLabelFormat() {
			this(new String[] { "Custom Suffix" }, "Custom Format");
		}

		protected CustomLabelFormat(String[] allowedFormatUnits, String name) {
			super(allowedFormatUnits, name, true, false);
		}

		@Override
		public String suffix() {
			return customSuffix;
		}
	}

	/**
	 * A panel containing several font manipulation items. It consists of a type
	 * face chooser, font size chooser, style chooser and a checkbox to
	 * enable/disable anti-aliased text (smoothing).
	 */
	@SuppressWarnings("serial")
	protected class FontPropertiesPanel extends JPanel{
		/**
		 * Creates a new {@link FontPropertiesPanel} containing the font setting
		 * and font colour buttons.
		 */
		public FontPropertiesPanel() {
			super(null);
			
			JButton fontStyleButton = new JButton("Font Settings");
			fontStyleButton.setBounds(0, 0, 120, 25);
			fontStyleButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent actionEvent) {
					new ExtendedFonts();

				}
			});
			
			JButton fontColourButton = new JButton("Font Color");
			fontColourButton.setBounds(130, 0, 120, 25);
			fontColourButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent actionEvent) {
					new ColorPicker();

				}
			});
			
			final JCheckBox drawBackground = new JCheckBox("Background");
			drawBackground.setBounds(260, 0, 120, 25);
			drawBackground.addItemListener(new ItemListener() {
				public void itemStateChanged(ItemEvent e) {
					backgroundEnabled = drawBackground.isSelected();
					updatePreview(e);
				}
			});
			
			add(fontStyleButton);
			add(fontColourButton);
			add(drawBackground);
		}
	}
	
	@SuppressWarnings("serial")
	protected class ExtendedFonts extends Fonts{
		@Override
		public void itemStateChanged(ItemEvent e) {
			super.itemStateChanged(e);
			font = new Font(TextRoi.getFont(), TextRoi.getStyle(), TextRoi.getSize());
			antiAliasedText = TextRoi.isAntialiased();
			updatePreview(e);
		}
		
	}
} // thats the end of Time_Stamper_Enhanced class

