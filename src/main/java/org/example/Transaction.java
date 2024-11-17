package org.example;
public class Transaction implements Comparable<Transaction> {
    private final int id;
    private final double amount;
    private final double priority;
    public Transaction(int id, double amount, double priority) {
        this.id = id;
        this.amount = amount;
        this.priority = priority;
    }
    public int getId() {
        return id;
    }
    public double getAmount() {
        return amount;
    }
    public double getPriority() {
        return priority;
    }
    @Override
    public int compareTo(Transaction other) {
        return Double.compare(other.priority, this.priority); // Пріоритет вищий для більших значень
    }
    @Override
    public String toString() {
        return "Transaction{id=" + id + ", amount=" + amount + ", priority=" + priority + '}';
    }
}
