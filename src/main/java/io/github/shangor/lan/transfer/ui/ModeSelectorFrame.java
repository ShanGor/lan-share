package io.github.shangor.lan.transfer.ui;

import io.github.shangor.lan.transfer.ui.receiver.ReceiverFrame;
import io.github.shangor.lan.transfer.ui.sender.SenderFrame;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

public class ModeSelectorFrame extends JFrame {

    public ModeSelectorFrame() {
        super("Lan Transfer - Select Mode");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JLabel title = new JLabel("Choose a mode to start", SwingConstants.CENTER);
        add(title, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 12));
        JButton senderButton = new JButton("Sender");
        JButton receiverButton = new JButton("Receiver");
        buttons.add(senderButton);
        buttons.add(receiverButton);
        add(buttons, BorderLayout.SOUTH);

        senderButton.addActionListener(e -> {
            new SenderFrame().setVisible(true);
            dispose();
        });

        receiverButton.addActionListener(e -> {
            new ReceiverFrame().setVisible(true);
            dispose();
        });

        setSize(360, 160);
        setLocationRelativeTo(null);
        setVisible(true);
    }
}
