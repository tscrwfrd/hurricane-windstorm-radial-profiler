package cyclone;

import org.jblas.FloatMatrix;

import java.util.ArrayList;

import static cyclone.Parameters.*;


public class WilloughbyEqns11 extends TropicalCycloneModel {



    public WilloughbyEqns11(float cellSize, String FileOuputPath, ArrayList<Float> bounds) {
        super(cellSize, FileOuputPath, bounds);

    }

    @Override
    public void runModel() {

        float ye = -55.0f;
        float xe = -40.0f;
        float cs = 18.0f;
        float Mx = 125.3f;
        float Rx = 22.3f;

        // TODO: next known position could be another lat/lon - true bearing?
        float course = 283.0f; // true bearing

        //next
        float nxe = -39.5f;
        float nye = 55.8f;

        // position delta values
//        float dy = nye - ye;
        float dx = nxe - xe;

        // atan2 handles zero conditions
        float chi = (float) Math.atan2(Math.sin(dx * (PI / 180f)) * Math.cos(nye * (PI / 180f)),
                Math.cos(ye * (PI / 180f)) * Math.sin(nye * (PI / 180.0f)) -
                        Math.sin(ye * (PI / 180f)) * Math.cos(nye * (PI / 180f)) *
                                Math.cos(dx * (PI / 180f)));
        chi = chi % (2.0f * PI);
        chi = chi * (180.0f / PI);

        // chi represents hurricane direction relative to longitudinal direction
        if (chi < -90.0f)
            chi = 180f + (-1f) * chi;
        else if (chi < 0.0f)
            chi = 90f + (-1f) * chi;
        else if (chi > 90f)
            chi = chi * (-1f) + 90f;
        else
            chi = 90f - chi;


        // removing the translation speed component:
        //   Phadke AC, Martino CD, Cheung KF, and Houston SH. 2003. Modeling of
        //   tropical cyclone winds and waves for emergency management. Ocean
        //   Engineering 30(4):553-578.
        //
        // this is not an agreed on value within the hurricane research community
        cs = KNOTS_TO_MPS * cs;
        float Vmax = KNOTS_TO_MPS * Mx - cs;

        // convert MWR to kilometers
        float Rmax = Rx * NM_TO_KM;

        // equation 11(a)
        float X1 = 287.6f - 1.942f * Vmax + 7.799f * (float) Math.log(Rmax) + 1.1819f * ye;

        // recommended set at 25.0
        float X2 = 25.0f;

        // equation 11(b)
        float n = 2.134f + 0.0077f * Vmax - 0.4522f * (float) Math.log(Rmax) - 0.0038f * ye;

        // equation 11(c) A >= 0
        float A = 0.5913f + 0.0029f * Vmax - 0.1361f * (float) Math.log(Rmax) - 0.0042f * ye;

        if (A < 0.0) {
            A = 0.0f;
        }

        float w = n * ((1f - A) * X1 + A * X2) / (n * ((1f - A) * X1 + A * X2) + Rmax);

        // newton-raphson routine - inverting equation 2
        float xi_holder = 0.5f;
        int num = 1;
        int MAX_ITERATIONS = 1000;
        float xi = 0.0f;

        for (int i = 0; i < MAX_ITERATIONS; i++) {

            xi = xi_holder - (wf(xi_holder, w) / dwf(xi_holder));

            if (Math.abs(xi - xi_holder) < TOLERANCE)
                break;

            xi_holder = xi;
            num = num + 1;

            if (num == MAX_ITERATIONS)
                System.out.println("WARNING: numerical value of xi did not converge.");

        }


        // ensure 0<=w<=1
//        if (xi <= 0.0f)
//            w = 0.0f;
//        else if (xi >= 1.0f)
//            w = 1.0f;


        // "...width of the transition is specified a priori, between 10 and 25 km."
        // pg.1104, paragraph 2
        //
        // calculating radius to start transition
        float R1, R2;
        if (Rmax > 20.0f) {
            R1 = Rmax - 25.0f * xi;
            R2 = R1 + 25.0f;
        } else {
            R1 = Rmax - 10.0f * xi;
            R2 = R1 + 10.0f;
        }

        FloatMatrix grid = new FloatMatrix(longitudes.columns, latitudes.rows);
        float theta=0.0f;

        for (int i = 0; i < lonSize; i++)
            for (int j = 0; j < latSize; j++) {

                // The angle, from the eye to a grid point(lon,lat), measure relative to longitudinal direction
               //   K.A. Werley and A.W. McCown. Estimating cyclone wind decay over land. Technical report,
               //   Los Alamos National Laboratory (LANL), Los Alamos, NM (United States), 2007.
                float phi = (float) (180f / PI * Math.atan((latitudes.get(i + lonSize * j) - ye) /
                        (longitudes.get(i + lonSize * j) - xe) /
                        Math.cos(PI * ((latitudes.get(i + lonSize * j) + ye) / 2.0f) / 180f)));


                if ((longitudes.get(i + lonSize * j) - xe) < 0.0)
                    phi = phi + 180f;


                // lat/lon bearing from storm eye
                theta = phi - chi + 90.0f;
                float range = (float) (Math.pow(Math.sin((latitudes.get(i + lonSize * j) - ye) * (PI / 180.0) / 2.0), 2.0) +
                        Math.cos(latitudes.get(i + lonSize * j) * (PI / 180.0)) * Math.cos(ye * (PI / 180.0)) *
                                Math.pow(Math.sin((longitudes.get(i + lonSize * j) - xe) * (PI / 180.0) / 2.0), 2.0));

                range = (float) Math.sqrt(range);
                range = (float) (2f * EARTH_RADIUS * Math.asin(range));
                System.out.println(range);

                float V0 = (float) (Vmax * ((1.0f - A) * Math.exp(-(range - Rmax) / X1) +
                        A * Math.exp(-(range - Rmax) / X2)));

                xi = (range - R1) / (R2 - R1);
                w = wf(xi, 0.0f);
                float velocity;

                if (range <= R1)
                    velocity = (float) ( Vmax * Math.pow((range / Rmax), n));
                else if (range > R1 && range < R2)
                    velocity = (float) ( Vmax * Math.pow((range / Rmax), n) * (1 - w) + V0 * w);
                else
                    velocity = V0;

                // TODO: add land mask features for wind reduction
                // Wind inflow angle
                //   Phadke AC, Martino CD, Cheung KF, and Houston SH. 2003. Modeling of
                //   tropical cyclone winds and waves for emergency management. Ocean
                //   Engineering 30(4):553-578.
                //   equations 11(a-c)

                float beta_angle;
                if (range < Rmax)
                    beta_angle = 10.0f * (1.0f + range / Rmax);
                else if (Rmax <= range && range < 1.2f * Rmax)
                    beta_angle = 20.0f + 25.0f * ((range / Rmax) - 1.0f);
                else
                    beta_angle = 25.0f;

                // Accounting for forward motion of Hurricane - equation 12
                //   Phadke AC, Martino CD, Cheung KF, and Houston SH. 2003. Modeling of
                //   tropical cyclone winds and waves for emergency management. Ocean
                //   Engineering 30(4):553-578.
                //
                // each component <u,v> => <v*cos(), v*sin()>
                // mod function used to ensure degree adjustment is 0<=deg<=360

                float wind_u = (float) (velocity * Math.cos((theta + beta_angle) % 360f) * (PI / 180f));
                float wind_v = (float) (velocity * Math.sin((theta + beta_angle) % 360f) * (PI / 180f));

                // correction factor
                float cf = (2.0f * Rmax * range) / (Rmax * Rmax + range * range);

                // add in correction factor by component
                wind_u = cf * wind_u;
                wind_v = cf * wind_v;

                // asymmetric factor
                float asymf = (float) (cf * cs * Math.cos(theta * (PI / 180f)));

                // total wind speed value
                velocity = (float) (Math.sqrt(wind_u * wind_u + wind_v * wind_v) + asymf);


                grid.put(i + lonSize * j, velocity);

            }

        System.out.println(grid);
        System.out.println("  chi: "+chi);
        System.out.println("theta: "+theta);
        System.out.println("theta: "+theta);

    }

    @Override
    public void writeRaster() {
        System.out.println(" --- " + this.latitudes.columns);
    }



    private float wf(float x, float val) {

        // powers of x-value
        float x5 = x * x * x * x * x;
        float x6 = x * x * x * x * x * x;
        float x7 = x * x * x * x * x * x * x;
        float x8 = x * x * x * x * x * x * x * x;
        float x9 = x * x * x * x * x * x * x * x * x;

        //solution
        float soln = 126 * x5 - 420 * x6 + 540 * x7 - 315 * x8 + 70 * x9 - val;

        return soln;
    }


    private float dwf(float x) {

        // powers of x-value
        float x4 = x * x * x * x;
        float x5 = x * x * x * x * x;
        float x6 = x * x * x * x * x * x;
        float x7 = x * x * x * x * x * x * x;
        float x8 = x * x * x * x * x * x * x * x;

        //solution
        float soln = 630 * x4 - 2520 * x5 + 3780 * x6 - 2520 * x7 + 630 * x8;


        return soln;
    }

}
