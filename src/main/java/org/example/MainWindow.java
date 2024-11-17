package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MainWindow {
    private JPanel panelMain;
    private JTextField threadCountField;
    private JTextField transactionCountField; // Поле для кількості транзакцій
    private JButton startButton;
    private JButton clearButton;
    private JTable transactionTable;
    private JLabel totalTimeLabel;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> rowSorter;

    private PriorityBlockingQueue<Transaction> transactionQueue;
    private ExecutorService threadPool;
    private ExecutorService retryPool;
    private AtomicInteger transactionCounter;
    private Connection dbConnection;

    public MainWindow() {
        // Ініціалізація черги
        transactionQueue = new PriorityBlockingQueue<>();

        // Ініціалізація панелі
        panelMain = new JPanel(new BorderLayout());

        // Ініціалізація компонентів
        threadCountField = new JTextField(5);
        transactionCountField = new JTextField(5); // Поле для кількості транзакцій
        startButton = new JButton("Start");
        clearButton = new JButton("Clear Data");
        totalTimeLabel = new JLabel("Total Time: 0 ms");

        // Ініціалізація таблиці
        tableModel = new DefaultTableModel(
                new String[]{"Transaction ID", "Amount", "Priority", "Thread Name", "Status", "Execution Time (ms)"}, 0);
        transactionTable = new JTable(tableModel);

        // Додавання сортування таблиці
        rowSorter = new TableRowSorter<>(tableModel);
        transactionTable.setRowSorter(rowSorter);

        // Додавання компонентів у панель
        JPanel controlPanel = new JPanel(new FlowLayout());
        controlPanel.add(new JLabel("Threads:"));
        controlPanel.add(threadCountField);
        controlPanel.add(new JLabel("Transactions:"));
        controlPanel.add(transactionCountField); // Додаємо поле для кількості транзакцій
        controlPanel.add(startButton);
        controlPanel.add(clearButton);
        panelMain.add(controlPanel, BorderLayout.NORTH);

        JScrollPane tableScrollPane = new JScrollPane(transactionTable);
        panelMain.add(tableScrollPane, BorderLayout.CENTER);
        panelMain.add(totalTimeLabel, BorderLayout.SOUTH);

        // Налаштування дій для кнопок
        startButton.addActionListener(e -> {
            String threadCountText = threadCountField.getText();
            String transactionCountText = transactionCountField.getText();

            int threadCount = tryParseInt(threadCountText);
            int transactionCount = tryParseInt(transactionCountText);

            if (threadCount < 1 || transactionCount < 1) {
                JOptionPane.showMessageDialog(null, "Please enter valid numbers for threads and transactions.");
                return;
            }

            startProcessing(threadCount, transactionCount);
        });

        clearButton.addActionListener(e -> {
            clearData(); // Очищення таблиці та бази даних
        });

        // Ініціалізація бази даних
        initDatabase();

        // Відновлення черги з бази даних
        loadTransactionsFromDatabase();
    }

    private void initDatabase() {
        try {
            dbConnection = DriverManager.getConnection("jdbc:sqlite:transactions.db");
            Statement stmt = dbConnection.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY, amount REAL, priority REAL, status TEXT)");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadTransactionsFromDatabase() {
        try {
            PreparedStatement query = dbConnection.prepareStatement("SELECT id, amount, priority FROM transactions WHERE status = 'Pending'");
            ResultSet rs = query.executeQuery();
            while (rs.next()) {
                Transaction transaction = new Transaction(rs.getInt("id"), rs.getDouble("amount"), rs.getDouble("priority"));
                transactionQueue.add(transaction);
                tableModel.addRow(new Object[]{
                        transaction.getId(),
                        formatDouble(transaction.getAmount()),
                        formatDouble(transaction.getPriority()),
                        "Waiting",
                        "Queued",
                        ""
                });
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void saveTransactionToDatabase(Transaction transaction, String status) {
        try {
            PreparedStatement statement = dbConnection.prepareStatement(
                    "INSERT OR REPLACE INTO transactions (id, amount, priority, status) VALUES (?, ?, ?, ?)");
            statement.setInt(1, transaction.getId());
            statement.setDouble(2, transaction.getAmount());
            statement.setDouble(3, transaction.getPriority());
            statement.setString(4, status);
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void startProcessing(int threadCount, int transactionCount) {
        threadPool = Executors.newFixedThreadPool(threadCount);
        retryPool = Executors.newFixedThreadPool(2); // Пул для повторних спроб
        transactionCounter = new AtomicInteger(0);

        // Додавання нових транзакцій
        for (int i = 1; i <= transactionCount; i++) {
            double amount = Math.random() * 1_000_000; // Збільшено діапазон до 1 000 000
            double priority = Math.random();
            while (priority == 0.0) { // Гарантуємо, що пріоритет не буде 0
                priority = Math.random();
            }
            Transaction transaction = new Transaction(i, amount, priority);
            transactionQueue.add(transaction);
            saveTransactionToDatabase(transaction, "Pending");
            tableModel.addRow(new Object[]{
                    transaction.getId(),
                    formatDouble(transaction.getAmount()),
                    formatDouble(transaction.getPriority()),
                    "Waiting",
                    "Queued",
                    ""
            });
        }

        // Обробка транзакцій потоками
        for (int i = 0; i < threadCount; i++) {
            threadPool.submit(() -> {
                while (!transactionQueue.isEmpty()) {
                    try {
                        Transaction transaction = transactionQueue.poll(1, TimeUnit.SECONDS);
                        if (transaction != null) {
                            processTransaction(transaction);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // Завершення роботи пулу
        new Thread(() -> {
            try {
                threadPool.shutdown();
                threadPool.awaitTermination(10, TimeUnit.MINUTES);
                SwingUtilities.invokeLater(() -> totalTimeLabel.setText("Processing completed."));
                retryPool.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void processTransaction(Transaction transaction) {
        long transactionStartTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();

        SwingUtilities.invokeLater(() -> {
            int rowIndex = transaction.getId() - 1;
            tableModel.setValueAt(threadName, rowIndex, 3);
            tableModel.setValueAt("Processing", rowIndex, 4);
        });

        try {
            // Симуляція обробки транзакції
            Thread.sleep((long) (transaction.getAmount() % 1000));
        } catch (InterruptedException e) {
            retryTransaction(transaction);
            Thread.currentThread().interrupt();
            return;
        }

        long transactionEndTime = System.currentTimeMillis();
        long executionTime = transactionEndTime - transactionStartTime;

        saveTransactionToDatabase(transaction, "Completed");

        SwingUtilities.invokeLater(() -> {
            int rowIndex = transaction.getId() - 1;
            tableModel.setValueAt("Completed", rowIndex, 4);
            tableModel.setValueAt(executionTime, rowIndex, 5);
        });

        transactionCounter.incrementAndGet();
    }

    private void retryTransaction(Transaction transaction) {
        retryPool.submit(() -> {
            try {
                Thread.sleep(2000); // Затримка перед повторною спробою
                processTransaction(transaction);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void clearData() {
        // Очищення таблиці
        tableModel.setRowCount(0);

        // Очищення бази даних
        try {
            PreparedStatement statement = dbConnection.prepareStatement("DELETE FROM transactions");
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Очищення черги транзакцій
        transactionQueue.clear();

        JOptionPane.showMessageDialog(null, "All data has been cleared.");
    }

    private String formatDouble(double value) {
        String formattedValue = String.format("%.2f", value); // Форматування до 2 знаків після коми
        if (formattedValue.endsWith(".00")) { // Якщо немає дробової частини, видаляємо ".00"
            return formattedValue.substring(0, formattedValue.indexOf('.'));
        }
        return formattedValue;
    }

    private int tryParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void cleanUp() {
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        if (retryPool != null) {
            retryPool.shutdownNow();
        }
        if (dbConnection != null) {
            try {
                dbConnection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        MainWindow mainWindow = new MainWindow();
        JFrame frame = new JFrame("Transaction Processor");
        frame.setContentPane(mainWindow.panelMain);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                mainWindow.cleanUp(); // Завершення потоків і закриття бази
                frame.dispose(); // Закриття вікна
            }
        });
        frame.setSize(800, 600);
        frame.setVisible(true);
    }
}
