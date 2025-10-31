public class App {
    public static void main(String[] args) throws Exception {
        // get the top 10 crimes in Chicago using the SODA3 resource endpoint
        HTTPApi api = new HTTPApi();
        String report = api.fetchTopCrimes(10);
        System.out.println(report);
    }
}
