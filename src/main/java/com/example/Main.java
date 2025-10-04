package com.example;

import com.example.api.ElpriserAPI;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: --zone SE1|SE2|SE3|SE4 [--date YYYY-MM-DD] [--sorted] [--charging 2h|4h|8h] [--help]");
            return;
        }
        ElpriserAPI elpriserAPI = new ElpriserAPI();
        ElpriserAPI.Prisklass prisklass;

        String zone = "";
        LocalDate date = LocalDate.now();
        List<ElpriserAPI.Elpris> priser = new ArrayList<>();
        boolean sortedRequested = false;
        boolean helpRequested = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help":
                    helpRequested = true;
                    break;

                case "--sorted":
                    sortedRequested = true;
                    break;

                case "--zone":
                    if (i + 1 < args.length) {
                        zone = args[i + 1].toUpperCase();
                        i++;
                    }
                    break;

                case "--date":
                    if (i + 1 < args.length) {
                        try {
                            date = LocalDate.parse(args[i + 1], DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                            i++;
                        } catch (DateTimeParseException e) {
                            System.out.println("Invalid date");
                            return;

                        }
                    }
                    break;
            }

                if (helpRequested) {
                    System.out.println ("--zone SE1|SE2|SE3|SE4 (required");
                    System.out.println ("--date YYYY-MM-DD (optional, defaults to current date");
                    System.out.println ("--sorted (optional, to display prices in descending order");
                    System.out.println ("--charging 2h|4h|8h (optional, to find optimal charging windows");
                    System.out.println("--help (optional, to display usage information");
                    return;
            }
            if (zone.isEmpty()) {
                System.out.println("Missing zone");
                return;

            }

            try {
                priser = elpriserAPI.getPriser(date, ElpriserAPI.Prisklass.valueOf(zone));
            } catch (IllegalArgumentException e) {
                System.out.println("Invalid zone");
                return;
            }


            if (!priser.isEmpty()) {

                ElpriserAPI.Elpris minPris = priser.get(0);
                ElpriserAPI.Elpris maxPris = priser.get(0);
                double sumPris = 0;

                for (ElpriserAPI.Elpris p : priser) {
                    double pris = p.sekPerKWh();
                    if (pris < minPris.sekPerKWh()) minPris = p;
                    if (pris > maxPris.sekPerKWh()) maxPris = p;
                    sumPris += pris;
                }
                double medelPris = sumPris / priser.size();

                System.out.println("Lägsta pris: "
                        + String.format("%.2f", minPris.sekPerKWh() * 100).replace('.', ',')
                        + " öre (" + String.format("%02d", minPris.timeStart().getHour())
                        + "-" + String.format("%02d", minPris.timeEnd().getHour()) + ")");

                System.out.println("Högsta pris: "
                        + String.format("%.2f", maxPris.sekPerKWh() * 100).replace('.', ',')
                        + " öre (" + String.format("%02d", maxPris.timeStart().getHour())
                        + "-" + String.format("%02d", maxPris.timeEnd().getHour()) + ")");

                System.out.println("Medelpris: "
                        + (Math.round(medelPris * 1000) / 10.0)
                        + " öre");

                if (sortedRequested) {
                    List<ElpriserAPI.Elpris> sortedPriser = new ArrayList<>(priser);
                    sortedPriser.sort(Comparator.comparingDouble(ElpriserAPI.Elpris::sekPerKWh));

                    for (ElpriserAPI.Elpris p : sortedPriser) {
                        String timeRange = String.format("%02d-%02d", p.timeStart().getHour(), p.timeEnd().getHour());
                        String priceOre = String.format("%.2f", p.sekPerKWh() * 100).replace('.', ',');
                        System.out.println(timeRange + " " + priceOre + " öre");}
                }

            }
            else {
                System.out.println("Inga priser tillgängliga");

            }}

         if (Arrays.asList(args).contains("--charging")) {
                int windowHours = 0;
                for (int j = 0; j < args.length; j++) {
                    if ("--charging".equals(args[j]) && j + 1 < args.length) {
                        String val = args[j + 1].toLowerCase();
                        if (val.equals("2h")) windowHours = 2;
                        else if (val.equals("4h")) windowHours = 4;
                        else if (val.equals("8h")) windowHours = 8;
                    }
                }

            if (windowHours > 0) {
                System.out.println("Påbörja laddning");

                List<ElpriserAPI.Elpris> allPrices = new ArrayList<>(priser);

                try {
                    List<ElpriserAPI.Elpris> nextDayPrices = elpriserAPI.getPriser(date.plusDays(1), ElpriserAPI.Prisklass.valueOf(zone));
                    allPrices.addAll(nextDayPrices);
                } catch (IllegalArgumentException e) {

                }

                // Loop för att hitta billigaste fönster
                double minTotal = Double.MAX_VALUE;
                int startIndex = 0;

                for (int k = 0; k <= allPrices.size() - windowHours; k++) {
                    double total = 0;
                    for (int j = 0; j < windowHours; j++) {
                        total += allPrices.get(k + j).sekPerKWh() * 100; // Öre
                    }
                    if (total < minTotal) {
                        minTotal = total;
                        startIndex = k;
                    }
                }
                // Skriv ut start och slut på fönstret
                ElpriserAPI.Elpris start = allPrices.get(startIndex);
                ElpriserAPI.Elpris end = allPrices.get(startIndex + windowHours - 1);

                String startTime = String.format("%02d:%02d", start.timeStart().getHour(), start.timeStart().getMinute());
                String endTime = String.format("%02d:%02d", end.timeEnd().getHour(), end.timeEnd().getMinute());

                System.out.println("Billigaste fönster: " + startTime + "-" + endTime
                        + " Totalkostnad: " + String.format("%.2f", minTotal).replace('.', ',') + " öre");


                System.out.println("Påbörja laddning kl " + startTime);
                double windowMean = minTotal / windowHours; // Medelpris i öre
                String windowMeanStr = String.format("%.2f", windowMean).replace('.', ',');
                System.out.println("Medelpris för fönster: " + windowMeanStr + " öre");

            }


        }
    } }