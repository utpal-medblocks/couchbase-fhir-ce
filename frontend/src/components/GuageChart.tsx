import { PieChart, Pie, Cell, ResponsiveContainer, Customized } from "recharts";
import { Box, Typography, useTheme } from "@mui/material";

const RADIAN = Math.PI / 180;
const GAUGE_SEGMENTS: {
  value: number;
  colorKey: "success" | "warning" | "error";
}[] = [
  { value: 40, colorKey: "success" },
  { value: 40, colorKey: "warning" },
  { value: 20, colorKey: "error" },
];

export const GaugeChart = ({
  name,
  value,
}: {
  name: string;
  value: number;
}) => {
  const theme = useTheme();

  const needle = (
    value: number,
    cx: number,
    cy: number,
    iR: number,
    oR: number,
    color: string | undefined
  ) => {
    let total = 100;
    const ang = 180.0 * (1 - value / total);
    const length = (iR + 2 * oR) / 3;
    const sin = Math.sin(-RADIAN * ang);
    const cos = Math.cos(-RADIAN * ang);
    // const r = 5;
    const r = oR * 0.08; // or adjust 0.08 to your preferred relative size
    const x0 = cx;
    const y0 = cy;
    const xba = x0 + r * sin;
    const yba = y0 - r * cos;
    const xbb = x0 - r * sin;
    const ybb = y0 + r * cos;
    const xp = x0 + length * cos;
    const yp = y0 + length * sin;

    return [
      <circle cx={x0} cy={y0} r={r} fill={color} stroke="none" />,
      <path
        d={`M${xba} ${yba}L${xbb} ${ybb} L${xp} ${yp} L${xba} ${yba}`}
        stroke="#none"
        fill={color}
      />,
    ];
  };
  // End of needle rendering logic

  const segments = GAUGE_SEGMENTS.map(({ value, colorKey }) => ({
    value,
    fill: theme.palette[colorKey].main,
  }));

  return (
    <Box sx={{ width: "100%", height: 150, position: "relative" }}>
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie
            data={segments}
            startAngle={180}
            endAngle={0}
            innerRadius="70%"
            outerRadius="100%"
            dataKey="value"
            stroke="none"
            cx="50%"
            cy="50%" // Pushes the semicircle up a bit
            isAnimationActive={false}
          >
            {segments.map((entry, index) => (
              <Cell key={`cell-${index}`} fill={entry.fill} />
            ))}
          </Pie>
          <Customized
            component={(props: any) => {
              const { width, height } = props;

              const cx = width / 2;
              const cy = height / 2;
              const iR = height * 0.27; // approx 40 for height = 150
              // const oR = height * 0.4; // approx 60 for height = 150
              const oR = Math.min(width, height) * 0.5;
              return needle(value, cx, cy, iR, oR, theme.palette.primary.main);
            }}
          />
        </PieChart>
      </ResponsiveContainer>

      {/* Centered label inside gauge */}
      <Box
        sx={{
          position: "absolute",
          top: "65%",
          left: "50%",
          transform: "translate(-50%, -50%)",
          textAlign: "center",
          pointerEvents: "none",
          display: "flex",
          flexDirection: "column",
        }}
      >
        <Typography
          variant="body2"
          color="text.secondary"
          sx={{ lineHeight: 1.0 }}
        >
          {name}
        </Typography>
        <Typography variant="h6" sx={{ lineHeight: 1.0 }}>
          {value.toFixed(1)}%
        </Typography>
      </Box>
    </Box>
  );
};
export default GaugeChart;
