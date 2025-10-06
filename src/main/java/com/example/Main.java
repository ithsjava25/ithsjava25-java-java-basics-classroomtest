package com.example;

import com.example.api.ElpriserAPI;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class Main {

    public static void main(String[] args) {
        Locale.setDefault(new Locale("sv", "SE"));
        if (args.length == 0 || List.of(args).contains("--help")) {
            printHelp(); return;
        }

        ElpriserAPI.Prisklass zone = null;
        LocalDate date = LocalDate.now();
        boolean sorted = false;
        Integer chargingHours = null;

        // Simplified Argument Parsing
        for (int i = 0; i < args.length; i++) {
            try {
                switch (args[i]) {
                    case "--zone": zone = ElpriserAPI.Prisklass.valueOf(args[++i].toUpperCase(Locale.ROOT)); break;
                    case "--date": date = LocalDate.parse(args[++i]); break;
                    case "--sorted": sorted = true; break;
                    case "--charging": {
                        String dur = args[++i].toLowerCase(Locale.ROOT).replace("h", "").trim();
                        int h = Integer.parseInt(dur);
                        if (h != 2 && h != 4 && h != 8) throw new IllegalArgumentException();
                        chargingHours = h;
                        break;
                    }
                }
            } catch (Exception e) {
                String near = (i < args.length ? args[i] : "");
                System.err.println("Error parsing argument near " + near);
                // Print user-facing messages to stdout because tests capture stdout only
                if (i > 0 && "--zone".equals(args[i - 1])) {
                    System.out.println("Invalid zone");
                } else if (i > 0 && "--date".equals(args[i - 1])) {
                    System.out.println("Invalid date");
                } else if (i > 0 && "--charging".equals(args[i - 1])) {
                    System.out.println("Invalid charging duration");
                } else {
                    System.out.println("Invalid arguments");
                }
                return;
            }
        }

        if (zone == null) { System.out.println("--zone is required"); return; }

        // --- Data Fetching and Normalization ---
        var api = new ElpriserAPI(false);
        List<ElpriserAPI.Elpris> prices = new ArrayList<>(api.getPriser(date, zone));
        prices.addAll(api.getPriser(date.plusDays(1), zone));
        if (prices.isEmpty()) { System.out.println("No data available for given date/zone."); return; }

        prices = normalizeToHourly(prices);

        // --- Reporting (Min, Max, Avg) ---
        ElpriserAPI.Elpris min = null, max = null;
        double sum = 0;
        int n = prices.size();
        for (int i = 0; i < n; i++) {
            ElpriserAPI.Elpris p = prices.get(i);
            sum += p.sekPerKWh();
            if (min == null || p.sekPerKWh() < min.sekPerKWh()
                    || (p.sekPerKWh() == min.sekPerKWh() && p.timeStart().isBefore(min.timeStart()))) {
                min = p;
            }
            if (max == null || p.sekPerKWh() > max.sekPerKWh()
                    || (p.sekPerKWh() == max.sekPerKWh() && p.timeStart().isAfter(max.timeStart()))) {
                max = p;
            }
        }
        double medel = n == 0 ? 0 : sum / n;

        if (min != null) System.out.println("Lägsta pris " + hh(min) + " " + formatOre(min.sekPerKWh()));
        if (max != null) System.out.println("Högsta pris " + hh(max) + " " + formatOre(max.sekPerKWh()));
        System.out.println("Medelpris: " + formatOre(medel) + " öre");

        if (sorted) {
            // Sort by price desc, then by time asc for stable ordering within equal prices
            prices.sort(Comparator
                    .comparing(ElpriserAPI.Elpris::sekPerKWh).reversed()
                    .thenComparing(ElpriserAPI.Elpris::timeStart));
            for (var p : prices) {
                System.out.println(p.timeStart().toLocalDate() + " " + hh(p) + " " + formatOre(p.sekPerKWh()) + " öre");
            }
        } else {
            // Re-sort by time if not already sorted by price for min/max
            prices.sort(Comparator.comparing(ElpriserAPI.Elpris::timeStart));
        }

        // --- Cheapest Charging Window ---
        if (chargingHours != null) {
            int start = findCheapestWindowStart(prices, chargingHours);
            if (start >= 0) {
                double avgSum = 0;
                for (int i = start; i < start + chargingHours; i++) {
                    avgSum += prices.get(i).sekPerKWh();
                }
                double avg = avgSum / chargingHours;

                ZonedDateTime s = prices.get(start).timeStart();
                System.out.println("Påbörja laddning kl " + String.format("%02d:00", s.getHour()));
                System.out.println("Medelpris för fönster: " + formatOre(avg) + " öre");
            }
        }
    }

    private static String hh(ElpriserAPI.Elpris p) {
        return String.format("%02d-%02d", p.timeStart().getHour(), p.timeEnd().getHour());
    }

    private static void printHelp() {
        System.out.println("""
                Usage: java -jar app --zone SE1|SE2|SE3|SE4 [--date YYYY-MM-DD] [--sorted] [--charging 2h|4h|8h]
                --zone SE1|SE2|SE3|SE4 (required)
                --date YYYY-MM-DD (optional, defaults to current date)
                --sorted (optional, to display prices in descending order)
                --charging 2h|4h|8h (optional, to find optimal charging windows)
                --help (optional, to display usage information)
                """);
    }

    private static String formatOre(double sekPerKWh) {
        DecimalFormat df = new DecimalFormat("0.00", new java.text.DecimalFormatSymbols(new Locale("sv", "SE")));
        return df.format(sekPerKWh * 100.0);
    }

    private static int findCheapestWindowStart(List<ElpriserAPI.Elpris> prices, int hours) {
        // prices MUST be sorted by time here. main() handles this.
        if (prices.size() < hours) return -1;

        // Initial window sum
        double currentSum = 0;
        for (int i = 0; i < hours; i++) {
            currentSum += prices.get(i).sekPerKWh();
        }

        double bestSum = currentSum;
        int bestIdx = 0;

        // Sliding window calculation
        for (int i = hours; i < prices.size(); i++) {
            // Slide the window: add new element, subtract old element (i-hours)
            currentSum = currentSum + prices.get(i).sekPerKWh() - prices.get(i - hours).sekPerKWh();
            if (currentSum < bestSum) {
                bestSum = currentSum;
                bestIdx = i - hours + 1;
            }
        }
        return bestIdx;
    }

    public static List<ElpriserAPI.Elpris> normalizeToHourly(List<ElpriserAPI.Elpris> prices) {
        if (prices == null || prices.size() < 4) return prices;

        prices.sort(Comparator.comparing(ElpriserAPI.Elpris::timeStart));

        long intervalMinutes = prices.get(0).timeStart().until(prices.get(1).timeStart(), ChronoUnit.MINUTES);

        if (intervalMinutes >= 60) return prices;

        List<ElpriserAPI.Elpris> hourlyPrices = new ArrayList<>();

        // Use a traditional loop stepping by 4
        for (int i = 0; i + 3 < prices.size(); i += 4) {
            List<ElpriserAPI.Elpris> hourBucket = prices.subList(i, i + 4);

            double sumSek = 0, sumEur = 0, sumExr = 0;
            for (var p : hourBucket) {
                sumSek += p.sekPerKWh();
                sumEur += p.eurPerKWh();
                sumExr += p.exr();
            }

            // Calculate averages without stream
            final int N = 4;
            double avgSek = sumSek / N;
            double avgEur = sumEur / N;
            double avgExr = sumExr / N;

            ZonedDateTime start = hourBucket.get(0).timeStart().truncatedTo(ChronoUnit.HOURS);
            ZonedDateTime end = start.plusHours(1);

            hourlyPrices.add(new ElpriserAPI.Elpris(avgSek, avgEur, avgExr, start, end));
        }

        return hourlyPrices;
    }
}