import java.io.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;


public class ExpertReviewsCLI {

    // --- Simple billing config ---
    static final double TAX_RATE = 0.18; // 18% GST-style tax
    static final String CURRENCY = "INR"; // label only

    enum Role { ADMIN, EXPERT, USER }
    enum ReviewType { EXPERT, USER }

    static class Product implements Serializable {
        Long id; String sku; String name; String brand; String category; String description;
        Double avgExpertScore; Double avgUserScore; // denormalized
        double price = 0.0;
        public String toString(){
            return String.format("[#%d] %s | %s | %s | %s | %.2f %s | Expert %.1f | User %.1f",
                    id, sku, name, brand, category, price, CURRENCY,
                    avgExpertScore == null ? Double.NaN : avgExpertScore,
                    avgUserScore == null ? Double.NaN : avgUserScore);
        }
    }

    static class AppUser implements Serializable {
        Long id; String username; String password; Role role = Role.USER;
        double expertiseTrust = 0.5;
        String expertiseDomain;
        public String toString(){
            return String.format("[#%d] %s (%s) trust=%.2f domain=%s", id, username, role, expertiseTrust, expertiseDomain);
        }
    }

    static class Review implements Serializable {
        Long id; Long productId; Long authorId; ReviewType type; int score; String body;
        int helpfulVotes; Instant createdAt = Instant.now();
        public String toString(){
            return String.format("[#%d] %s score=%d by user #%d at %s\n%s", id, type, score, authorId, createdAt, body);
        }
    }

    static class Order implements Serializable {
        Long id; Long userId; List<Long> productIds = new ArrayList<>(); // duplicates allowed = quantity
        double subtotal; double tax; double total; Instant createdAt = Instant.now();
        public String toString(){
            return String.format("Order #%d by User %d on %s\nProducts: %s\nSubtotal: %.2f %s, Tax: %.2f %s, Total: %.2f %s",
                    id, userId, createdAt, productIds, subtotal, CURRENCY, tax, CURRENCY, total, CURRENCY);
        }
    }

    static class DataStore implements Serializable {
        Map<Long, Product> products = new LinkedHashMap<>();
        Map<Long, AppUser> users = new LinkedHashMap<>();
        Map<Long, Review> reviews = new LinkedHashMap<>();
        Map<Long, Order> orders = new LinkedHashMap<>();
        AtomicLong productSeq = new AtomicLong(1);
        AtomicLong userSeq = new AtomicLong(1);
        AtomicLong reviewSeq = new AtomicLong(1);
        AtomicLong orderSeq = new AtomicLong(1);
        static void save(DataStore ds, String path) throws IOException {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path))) {
                oos.writeObject(ds);
            }
        }
        static DataStore load(String path) throws IOException, ClassNotFoundException {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(path))) {
                return (DataStore) ois.readObject();
            }
        }
    }

    static class ScoringService {
        final DataStore db;
        ScoringService(DataStore db){ this.db = db; }
        void recomputeProductScores(Long productId){
            Product p = db.products.get(productId);
            if(p == null) return;
            List<Review> experts = db.reviews.values().stream()
                    .filter(r -> r.productId.equals(productId) && r.type == ReviewType.EXPERT)
                    .collect(Collectors.toList());
            double wSum = 0, wx = 0;
            for(Review r: experts){
                AppUser u = db.users.get(r.authorId);
                double trust = (u == null ? 0.5 : Math.max(0.0, Math.min(1.0, u.expertiseTrust)));
                if(u != null && p.category != null && p.category.equalsIgnoreCase(u.expertiseDomain)){
                    trust *= 1.25;
                }
                wSum += trust; wx += trust * r.score;
            }
            p.avgExpertScore = experts.isEmpty() ? null : (wx / wSum);
            List<Review> users = db.reviews.values().stream()
                    .filter(r -> r.productId.equals(productId) && r.type == ReviewType.USER)
                    .collect(Collectors.toList());
            p.avgUserScore = users.isEmpty() ? null : users.stream().mapToInt(r -> r.score).average().orElse(Double.NaN);
        }
        void recomputeAll(){ for(Long pid: db.products.keySet()) recomputeProductScores(pid); }
    }

    static class ReviewService {
        final DataStore db; final ScoringService scoring;
        ReviewService(DataStore db, ScoringService scoring){ this.db = db; this.scoring = scoring; }
        Review addReview(AppUser user, Long productId, int score, String body, ReviewType type){
            if(type == ReviewType.EXPERT && user.role != Role.EXPERT)
                throw new SecurityException("Only EXPERT can post EXPERT review");
            if(score < 1 || score > 10) throw new IllegalArgumentException("Score must be 1..10");
            boolean exists = db.reviews.values().stream().anyMatch(r ->
                    r.productId.equals(productId) && r.authorId.equals(user.id) && r.type == type);
            if(exists) throw new IllegalStateException("You already posted this type of review for this product");
            if(!db.products.containsKey(productId)) throw new NoSuchElementException("Product not found");
            Review r = new Review();
            r.id = db.reviewSeq.getAndIncrement();
            r.productId = productId; r.authorId = user.id; r.type = type; r.score = score; r.body = body;
            db.reviews.put(r.id, r);
            scoring.recomputeProductScores(productId);
            return r;
        }
    }

    static class OrderService {
        final DataStore db;
        OrderService(DataStore db){ this.db = db; }
        Order createOrder(AppUser user, List<Long> productIds){
            Order o = new Order();
            o.id = db.orderSeq.getAndIncrement();
            o.userId = user.id;
            o.productIds.addAll(productIds);
            // compute subtotal from ids (duplicates => quantity)
            double subtotal = 0.0;
            for(Long pid: productIds){
                Product p = db.products.get(pid);
                if(p != null) subtotal += p.price;
            }
            o.subtotal = round2(subtotal);
            o.tax = round2(o.subtotal * TAX_RATE);
            o.total = round2(o.subtotal + o.tax);
            db.orders.put(o.id, o);
            return o;
        }
    }

    static class AuthService {
        final DataStore db; AppUser current;
        AuthService(DataStore db){ this.db = db; }
        AppUser signup(String username, String password, Role role){
            if(db.users.values().stream().anyMatch(u -> u.username.equalsIgnoreCase(username)))
                throw new IllegalArgumentException("Username already exists");
            AppUser u = new AppUser();
            u.id = db.userSeq.getAndIncrement();
            u.username = username; u.password = password; u.role = role;
            db.users.put(u.id, u); return u;
        }
        boolean login(String username, String password){
            Optional<AppUser> o = db.users.values().stream()
                    .filter(u -> u.username.equalsIgnoreCase(username) && Objects.equals(u.password, password))
                    .findFirst();
            current = o.orElse(null);
            return current != null;
        }
        void requireLogin(){ if(current == null) throw new SecurityException("Login first"); }
        AppUser me(){ return current; }
    }

    final DataStore db = new DataStore();
    final ScoringService scoring = new ScoringService(db);
    final ReviewService reviews = new ReviewService(db, scoring);
    final AuthService auth = new AuthService(db);
    final OrderService orders = new OrderService(db);
    final Scanner in = new Scanner(System.in);

    public static void main(String[] args){ new ExpertReviewsCLI().run(); }

    ExpertReviewsCLI(){ seed(); }

    void run(){
        System.out.println("\n=== Expert-Driven Product Reviews + Purchasing (Java-only) ===\n");
        loop: while(true){
            try {
                showMenu();
                System.out.print("Choose: ");
                String choice = in.nextLine().trim();
                switch (choice) {
                    case "1" -> listProducts();
                    case "2" -> addProduct();
                    case "3" -> registerUser();
                    case "4" -> login();
                    case "5" -> postExpertReview();
                    case "6" -> postUserReview();
                    case "7" -> viewProductDetails();
                    case "8" -> recomputeAll();
                    case "9" -> save();
                    case "10" -> load();
                    case "11" -> purchaseProducts();
                    case "12" -> listOrders();
                    case "0" -> { System.out.println("Bye"); break loop; }
                    default -> System.out.println("Unknown choice");
                }
            } catch (Exception e){
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    void showMenu(){
        System.out.println("\n-- MENU --");
        System.out.println("1) List products");
        System.out.println("2) Add product");
        System.out.println("3) Register user");
        System.out.println("4) Login");
        System.out.println("5) Post EXPERT review");
        System.out.println("6) Post USER review");
        System.out.println("7) View product details");
        System.out.println("8) Recompute all scores");
        System.out.println("9) Save DB to file");
        System.out.println("10) Load DB from file");
        System.out.println("11) Purchase products");
        System.out.println("12) View my orders");
        System.out.println("0) Exit");
        if(auth.me()!=null) System.out.println("Logged in as: "+auth.me());
    }

    void listProducts(){
        List<Product> list = new ArrayList<>(db.products.values());
        list.sort(Comparator.comparing((Product p) -> p.avgExpertScore == null ? -Double.MAX_VALUE : p.avgExpertScore).reversed());
        if(list.isEmpty()) { System.out.println("No products yet"); return; }
        list.forEach(p -> System.out.println(p));
    }

    void addProduct(){
        System.out.print("SKU: "); String sku = in.nextLine().trim();
        if(db.products.values().stream().anyMatch(p -> p.sku.equalsIgnoreCase(sku))) { System.out.println("SKU exists"); return; }
        System.out.print("Name: "); String name = in.nextLine().trim();
        System.out.print("Brand: "); String brand = in.nextLine().trim();
        System.out.print("Category: "); String category = in.nextLine().trim();
        System.out.print("Description: "); String desc = in.nextLine().trim();
        double price = promptDouble("Price (in " + CURRENCY + "): ", 0.01, 100000);
        Product p = new Product();
        p.id = db.productSeq.getAndIncrement(); p.sku = sku; p.name = name; p.brand = brand; p.category = category; p.description = desc; p.price = price;
        db.products.put(p.id, p);
        System.out.println("Added: "+p);
    }

    void registerUser(){
        System.out.print("Username: "); String u = in.nextLine().trim();
        System.out.print("Password: "); String p = in.nextLine().trim();
        System.out.print("Role (ADMIN/EXPERT/USER): "); String r = in.nextLine().trim().toUpperCase();
        Role role;
        try { role = Role.valueOf(r); } catch(Exception e){ System.out.println("Invalid role"); return; }
        AppUser user = auth.signup(u, p, role);
        if(role == Role.EXPERT){
            System.out.print("Expertise domain: "); user.expertiseDomain = in.nextLine().trim();
            user.expertiseTrust = 0.7;
        }
        System.out.println("Registered: "+user);
    }

    void login(){
        System.out.print("Username: "); String u = in.nextLine().trim();
        System.out.print("Password: "); String p = in.nextLine().trim();
        if(auth.login(u,p)) System.out.println("Logged in as "+auth.me()); else System.out.println("Login failed");
    }

    void postExpertReview(){
        auth.requireLogin(); if(auth.me().role != Role.EXPERT) { System.out.println("Experts only"); return; }
        Long pid = promptProductId(); if(pid==null) return;
        int score = promptInt("Score 1..10: ", 1, 10);
        System.out.print("Body: "); String body = in.nextLine();
        Review r = reviews.addReview(auth.me(), pid, score, body, ReviewType.EXPERT);
        System.out.println("Created: \n"+r);
    }

    void postUserReview(){
        auth.requireLogin();
        Long pid = promptProductId(); if(pid==null) return;
        int score = promptInt("Score 1..10: ", 1, 10);
        System.out.print("Body: "); String body = in.nextLine();
        Review r = reviews.addReview(auth.me(), pid, score, body, ReviewType.USER);
        System.out.println("Created: \n"+r);
    }

    void viewProductDetails(){
        Long pid = promptProductId(); if(pid==null) return;
        Product p = db.products.get(pid);
        System.out.println("\n== Product ==\n"+p+"\nDesc: "+p.description);
        System.out.println("\n-- Expert Reviews --");
        db.reviews.values().stream().filter(r -> r.productId.equals(pid) && r.type==ReviewType.EXPERT)
                .forEach(System.out::println);
        System.out.println("\n-- User Reviews --");
        db.reviews.values().stream().filter(r -> r.productId.equals(pid) && r.type==ReviewType.USER)
                .forEach(System.out::println);
    }

    void recomputeAll(){ scoring.recomputeAll(); System.out.println("Recomputed"); }

    void save(){
        try {
            System.out.print("File path to save: "); String path = in.nextLine().trim();
            DataStore.save(db, path); System.out.println("Saved to "+path);
        } catch (Exception e){ System.out.println("Save failed: "+e.getMessage()); }
    }

    void load(){
        try {
            System.out.print("File path to load: "); String path = in.nextLine().trim();
            DataStore loaded = DataStore.load(path);
            db.products = loaded.products; db.users = loaded.users; db.reviews = loaded.reviews; db.orders = loaded.orders;
            db.productSeq = loaded.productSeq; db.userSeq = loaded.userSeq; db.reviewSeq = loaded.reviewSeq; db.orderSeq = loaded.orderSeq;
            scoring.recomputeAll();
            System.out.println("Loaded from "+path);
        } catch (Exception e){ System.out.println("Load failed: "+e.getMessage()); }
    }

    void purchaseProducts(){
        auth.requireLogin();
        listProducts();
        System.out.println("Enter product ids (comma separated). Repeat an id to buy multiple quantities. Example: 1,1,2");
        System.out.print("Product ids: ");
        String s = in.nextLine().trim();
        if(s.isEmpty()){ System.out.println("Nothing entered"); return; }
        List<Long> ids = Arrays.stream(s.split(","))
                .map(String::trim).filter(x->!x.isEmpty())
                .map(Long::parseLong).filter(id -> db.products.containsKey(id))
                .toList();
        if(ids.isEmpty()){ System.out.println("No valid products"); return; }
        Order o = orders.createOrder(auth.me(), ids);
        String bill = generateBill(o);
        System.out.println("\n=== BILL ===\n" + bill + "\n============\n");
        saveBillToFile(o.id, bill);
    }

    void listOrders(){
        auth.requireLogin();
        db.orders.values().stream().filter(o -> o.userId.equals(auth.me().id)).forEach(o -> {
            System.out.println(o);
            System.out.println("Bill file: bill-"+o.id+".txt (if previously generated)");
        });
    }

    Long promptProductId(){
        listProducts();
        System.out.print("Enter product id: "); String s = in.nextLine().trim();
        try { Long id = Long.parseLong(s); if(!db.products.containsKey(id)) { System.out.println("Not found"); return null; } return id; }
        catch(Exception e){ System.out.println("Invalid id"); return null; }
    }

    int promptInt(String label, int min, int max){
        while(true){
            System.out.print(label); String s = in.nextLine().trim();
            try {
                int v = Integer.parseInt(s);
                if(v<min||v>max) throw new RuntimeException();
                return v;
            } catch(Exception e){
                System.out.println("Enter a number between "+min+" and "+max);
            }
        }
    }

    double promptDouble(String label, double min, double max){
        while(true){
            System.out.print(label); String s = in.nextLine().trim();
            try {
                double v = Double.parseDouble(s);
                if(v<min||v>max) throw new RuntimeException();
                return v;
            } catch(Exception e){
                System.out.println("Enter a number between "+min+" and "+max);
            }
        }
    }

    static double round2(double x){ return Math.round(x*100.0)/100.0; }

    String generateBill(Order o){
        AppUser buyer = db.users.get(o.userId);
        Map<Long, Long> qty = o.productIds.stream()
                .collect(Collectors.groupingBy(pid -> pid, LinkedHashMap::new, Collectors.counting()));
        StringBuilder sb = new StringBuilder();
        sb.append("Invoice No: ").append(o.id).append('\n');
        sb.append("Date: ").append(DateTimeFormatter.ISO_INSTANT.format(o.createdAt)).append('\n');
        sb.append("Buyer: ").append(buyer==null?"?":buyer.username).append(" (#").append(o.userId).append(")\n\n");
        sb.append(String.format("%-5s %-20s %-8s %-10s %-10s\n", "ID", "Product", "Qty", "Price", "Line Total"));
        sb.append("----------------------------------------------------------------\n");
        for(Map.Entry<Long, Long> e: qty.entrySet()){
            Long pid = e.getKey(); long q = e.getValue();
            Product p = db.products.get(pid);
            if(p==null) continue;
            double line = round2(p.price * q);
            sb.append(String.format("%-5d %-20s %-8d %-10.2f %-10.2f\n", pid, truncate(p.name,20), q, p.price, line));
        }
        sb.append("----------------------------------------------------------------\n");
        sb.append(String.format("%-30s %20.2f %s\n", "Subtotal:", o.subtotal, CURRENCY));
        sb.append(String.format("%-30s %20.2f %s\n", "Tax ("+(int)(TAX_RATE*100)+"%):", o.tax, CURRENCY));
        sb.append(String.format("%-30s %20.2f %s\n", "TOTAL:", o.total, CURRENCY));
        return sb.toString();
    }

    void saveBillToFile(Long orderId, String bill){
        String name = "bill-"+orderId+".txt";
        try (PrintWriter pw = new PrintWriter(new FileOutputStream(name))){
            pw.print(bill);
            System.out.println("Bill saved: "+name);
        } catch (Exception e){
            System.out.println("Could not save bill: "+e.getMessage());
        }
    }

    static String truncate(String s, int n){ return (s==null) ? "" : (s.length()<=n?s:s.substring(0,n-1)+"…"); }

    void seed(){
        // Admin & users
        AppUser admin = auth.signup("admin", "admin", Role.ADMIN);
        AppUser exp1 = auth.signup("alice", "pass", Role.EXPERT); exp1.expertiseDomain = "Laptops"; exp1.expertiseTrust = 0.8;
        AppUser exp2 = auth.signup("bob", "pass", Role.EXPERT);   exp2.expertiseDomain = "Mobiles"; exp2.expertiseTrust = 0.9;
        auth.signup("charlie", "pass", Role.USER);
        // Products with prices
        Product p1 = new Product(); p1.id=db.productSeq.getAndIncrement(); p1.sku="LP-001"; p1.name="ZenBook X"; p1.brand="Asutek"; p1.category="Laptops"; p1.description="Thin-and-light laptop."; p1.price = 79990; db.products.put(p1.id,p1);
        Product p2 = new Product(); p2.id=db.productSeq.getAndIncrement(); p2.sku="MB-100"; p2.name="Pixelate 9"; p2.brand="Googlo"; p2.category="Mobiles"; p2.description="Camera-focused phone."; p2.price = 65999; db.products.put(p2.id,p2);
        Product p3 = new Product(); p3.id=db.productSeq.getAndIncrement(); p3.sku="HP-250"; p3.name="HyperPods"; p3.brand="Pome"; p3.category="Audio"; p3.description="Wireless earbuds."; p3.price = 8990; db.products.put(p3.id,p3);
        scoring.recomputeAll();
    }
}
